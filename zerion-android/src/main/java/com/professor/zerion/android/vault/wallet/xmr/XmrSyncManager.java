package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Drives read-only synchronization of one open Monero wallet over Tor. Every
 * operation is owned by a token capturing (walletId, session epoch, session,
 * node list); a result is published only while that token is still the active
 * one AND {@link Ownership#isCurrent} confirms the vault is unlocked at the same
 * lock generation. A stale result from an old wallet, node, or session epoch is
 * discarded, so wallet A's refresh can never update wallet B.
 *
 * <p>Scanning is done by the wallet's own background refresh thread (started
 * via {@link MoneroEngine.Session#startRefresh()}), which scans to the tip and
 * then re-checks for new blocks on a fixed interval. This loop is a read-only
 * observer: it polls the scanned height (a local read) and, sparingly, the
 * daemon height, and publishes truthful state. It never issues an overlapping
 * refresh of its own. A user refresh request only wakes the wallet's refresh
 * thread; repeated requests coalesce into one flag and a request during an
 * active scan is a no-op, so concurrent native scans are impossible.
 *
 * <p>Node failure is judged from the wallet's own error status and a live
 * connectivity check, never from a short pause in height progress: over Tor a
 * batch of full blocks can take a while to arrive, and treating that as a dead
 * node caused constant, unnecessary reconnects. A long stall (no scanned-height
 * progress while behind the tip) first triggers a connectivity check and only
 * fails over when the node is disconnected or still delivers nothing after a
 * bounded maximum. Failover is sequential and bounded; when every node has been
 * tried without success the state becomes OFFLINE, never an endless spinner,
 * and never clearnet.
 *
 * <p>All calls in this class run on the single {@code sessionExecutor} shared
 * with the wallet's open/close path. A vault lock wins every race:
 * {@link #stop()} flips the token cancelled and interrupts the background scan;
 * the native close, queued on the same executor, then runs with no scan running.
 */
@NotNullByDefault
public final class XmrSyncManager {

	public interface Ownership {
		boolean isCurrent(String walletId, long epoch);
	}

	public interface Sink {
		void publish(XmrSyncStatus status);
	}

	public interface HistorySink {
		void publish(List<XmrTxInfo> history);
	}

	private static final long DEFAULT_SYNCING_POLL_MS = 1500;
	private static final long DAEMON_PROBE_SYNCING_MS = 60_000;
	private static final long DAEMON_PROBE_SYNCED_MS = 60_000;
	private static final long STORE_EVERY_BLOCKS = 2000;
	private static final long STORE_QUIESCE_MS = 3000;
	private static final int REFRESH_INTERVAL_MS = 20_000;
	private static final long DEFAULT_STALL_CHECK_MS = 5 * 60_000;
	private static final long DEFAULT_STALL_MAX_MS = 15 * 60_000;
	private static final int ERROR_STRIKES = 3;
	private static final int FAST_CYCLES_AFTER_REQUEST = 6;

	private final Executor sessionExecutor;
	private final Ownership ownership;
	private final Sink sink;
	private final long pollIntervalMs;
	private final long syncingPollMs;
	private final long stallCheckMs;
	private final long stallMaxMs;

	@Nullable
	private volatile HistorySink historySink;
	@Nullable
	private volatile Token active;
	private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> tasks =
			new java.util.concurrent.ConcurrentLinkedQueue<>();

	public XmrSyncManager(Executor sessionExecutor, Ownership ownership,
			Sink sink, long pollIntervalMs) {
		this(sessionExecutor, ownership, sink, pollIntervalMs,
				DEFAULT_SYNCING_POLL_MS, DEFAULT_STALL_CHECK_MS,
				DEFAULT_STALL_MAX_MS);
	}

	XmrSyncManager(Executor sessionExecutor, Ownership ownership, Sink sink,
			long pollIntervalMs, long syncingPollMs, long stallCheckMs,
			long stallMaxMs) {
		this.sessionExecutor = sessionExecutor;
		this.ownership = ownership;
		this.sink = sink;
		this.pollIntervalMs = pollIntervalMs;
		this.syncingPollMs = syncingPollMs;
		this.stallCheckMs = stallCheckMs;
		this.stallMaxMs = stallMaxMs;
	}

	public void setHistorySink(@Nullable HistorySink historySink) {
		this.historySink = historySink;
	}

	private static final class Token {
		final String walletId;
		final long epoch;
		final MoneroEngine.Session session;
		final List<XmrNode> nodes;
		final int torSocksPort;
		volatile boolean cancelled;
		volatile boolean refreshRequested;
		volatile boolean taskPending;
		int nodeIndex = -1;

		Token(String walletId, long epoch, MoneroEngine.Session session,
				List<XmrNode> nodes, int torSocksPort) {
			this.walletId = walletId;
			this.epoch = epoch;
			this.session = session;
			this.nodes = nodes;
			this.torSocksPort = torSocksPort;
		}
	}

	public void start(String walletId, long epoch, MoneroEngine.Session session,
			List<XmrNode> nodes, int torSocksPort) {
		Token t = new Token(walletId, epoch, session, nodes, torSocksPort);
		active = t;
		sessionExecutor.execute(() -> run(t));
	}

	/**
	 * Stop the active sync at once. Flips the token so no further result can be
	 * published and interrupts any in-flight scan; the native handle itself is
	 * closed separately on the session executor after this returns.
	 */
	public void stop() {
		Token t = active;
		active = null;
		if (t != null) {
			t.cancelled = true;
			try {
				t.session.pauseRefresh();
			} catch (Throwable ignored) {
			}
			try {
				t.session.stopRefresh();
			} catch (Throwable ignored) {
			}
		}
		Runnable r;
		while ((r = tasks.poll()) != null) {
			sessionExecutor.execute(r);
		}
	}

	/** True while a sync loop owns the session (connected or scanning). */
	public boolean isActive() {
		Token t = active;
		return t != null && !t.cancelled;
	}

	/**
	 * Run a short piece of session-thread work. While a sync loop owns the
	 * session executor the work is queued and run by the loop on its next cycle
	 * (the loop is woken); otherwise it is posted to the executor directly. This
	 * is the only way for other callers to reach the session thread without
	 * waiting for the loop to end.
	 */
	public void submit(Runnable task) {
		Token t = active;
		if (t != null && !t.cancelled) {
			tasks.add(task);
			t.taskPending = true;
		} else {
			sessionExecutor.execute(task);
		}
	}

	private void drainTasks(Token t) {
		t.taskPending = false;
		Runnable r;
		while ((r = tasks.poll()) != null) {
			if (!live(t)) {
				sessionExecutor.execute(r);
				continue;
			}
			try {
				r.run();
			} catch (Throwable ignored) {
			}
		}
	}

	/**
	 * Ask for an incremental refresh now. Coalesces: repeated calls set one flag
	 * that the loop consumes once; if the wallet is already mid-scan the wake is
	 * a no-op. Never reconstructs, never restarts from the restore height.
	 */
	public void requestRefresh() {
		Token t = active;
		if (t != null && !t.cancelled) {
			t.refreshRequested = true;
		}
	}

	private boolean live(Token t) {
		return t == active && !t.cancelled
				&& ownership.isCurrent(t.walletId, t.epoch);
	}

	private void publish(Token t, XmrSyncStatus status) {
		if (live(t)) {
			sink.publish(status);
		}
	}

	private void publishHistory(Token t) {
		HistorySink hs = historySink;
		if (hs == null || !live(t)) return;
		try {
			hs.publish(t.session.history());
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Publish the canonical wallet2 history to the UI only when its content has
	 * changed since the last publication, so an incoming transaction wallet2 has
	 * just learned about (including an unconfirmed one) and every confirmation
	 * change surface on the next poll cycle without waiting for a block-height
	 * change or a manual refresh. Returns the new fingerprint. wallet2 stays the
	 * only source of truth; nothing is invented locally.
	 */
	private String publishHistoryIfChanged(Token t, @Nullable String lastFp) {
		HistorySink hs = historySink;
		if (hs == null || !live(t)) return lastFp == null ? "" : lastFp;
		List<XmrTxInfo> history;
		try {
			history = t.session.history();
		} catch (Throwable ignored) {
			return lastFp == null ? "" : lastFp;
		}
		String fp = historyFingerprint(history);
		if (fp.equals(lastFp)) return fp;
		try {
			hs.publish(history);
		} catch (Throwable ignored) {
		}
		return fp;
	}

	static String historyFingerprint(List<XmrTxInfo> history) {
		StringBuilder sb = new StringBuilder(history.size() * 24);
		sb.append(history.size());
		for (XmrTxInfo tx : history) {
			sb.append('|').append(tx.txid).append(':')
					.append(tx.confirmations).append(':')
					.append(tx.pending ? 1 : 0).append(':')
					.append(tx.failed ? 1 : 0).append(':')
					.append(tx.amountAtomic).append(':')
					.append(tx.height).append(':')
					.append(tx.direction == XmrTxInfo.Direction.IN ? 1 : 0);
		}
		return sb.toString();
	}

	private void run(Token t) {
		try {
			if (t.torSocksPort <= 0) {
				publish(t, XmrSyncStatus.of(XmrSyncState.OFFLINE));
				return;
			}
			publish(t, XmrSyncStatus.of(XmrSyncState.STARTING_TOR));
			int idx = connectAnyFrom(t, 0);
			if (idx < 0) {
				publish(t, XmrSyncStatus.of(XmrSyncState.OFFLINE));
				return;
			}
			t.nodeIndex = idx;
			publish(t, connectedStatus(t, XmrSyncState.CONNECTED, 0, 0, 0, 0,
					false));
			startBackgroundScan(t);

			long lastWh = -1;
			long lastProgressAt = System.currentTimeMillis();
			long lastConnCheckAt = 0;
			long lastDaemonProbeAt = 0;
			long dh = 0;
			int errorStrikes = 0;
			int fastCycles = 0;
			String lastHistoryFp = null;
			long lastStoredHeight = 0;
			long lastBal = 0;
			long lastUnl = 0;
			while (live(t)) {
				drainTasks(t);
				long now = System.currentTimeMillis();
				if (t.refreshRequested) {
					t.refreshRequested = false;
					wakeRefresh(t);
					lastDaemonProbeAt = 0;
					fastCycles = FAST_CYCLES_AFTER_REQUEST;
				}
				boolean checking = fastCycles > 0;
				if (fastCycles > 0) fastCycles--;

				int status = safeStatus(t);
				long whRaw = safeLong(() -> t.session.blockchainHeight());

				long wh = whRaw == NativeMonero.LONG_ERR
						? Math.max(lastWh, 0) : whRaw;
				if (wh > 0 && wh - lastStoredHeight >= STORE_EVERY_BLOCKS) {
					if (persistCache(t)) lastStoredHeight = wh;
				}
				boolean synced = dh > 0 && wh >= dh;
				long probeEvery = synced ? DAEMON_PROBE_SYNCED_MS
						: DAEMON_PROBE_SYNCING_MS;
				if (dh <= 0 || now - lastDaemonProbeAt >= probeEvery) {
					long dhRaw = safeLong(() -> t.session.daemonHeight());

					if (dhRaw != NativeMonero.LONG_ERR) dh = dhRaw;
					lastDaemonProbeAt = now;
					synced = dh > 0 && wh >= dh;
				}
				if (wh > lastWh) {
					lastWh = wh;
					lastProgressAt = now;
				}
				if (!live(t)) return;

				boolean failed = false;
				if (status != 0) {
					errorStrikes++;
					if (errorStrikes >= ERROR_STRIKES
							|| (errorStrikes >= 2 && safeConn(t) != 1)) {
						failed = true;
					}
				} else {
					errorStrikes = 0;
				}
				if (!failed && !synced) {
					long stalled = now - lastProgressAt;
					if (stalled >= stallMaxMs) {
						failed = true;
					} else if (stalled >= stallCheckMs
							&& now - lastConnCheckAt >= stallCheckMs) {
						lastConnCheckAt = now;
						int conn = safeConn(t);
						if (conn != 1) failed = true;
					}
				}
				if (failed) {
					if (!live(t)) return;
					int next = failover(t);
					if (next < 0) {
						publish(t, XmrSyncStatus.of(XmrSyncState.OFFLINE));
						return;
					}
					t.nodeIndex = next;
					publish(t, connectedStatus(t, XmrSyncState.CONNECTED, 0, 0,
							0, 0, false));
					startBackgroundScan(t);
					lastWh = -1;
					lastProgressAt = System.currentTimeMillis();
					lastConnCheckAt = 0;
					lastDaemonProbeAt = 0;
					dh = 0;
					errorStrikes = 0;
					lastHistoryFp = null;
					continue;
				}

				long balRaw = safeLong(() -> t.session.balance(0));
				long unlRaw = safeLong(() -> t.session.unlockedBalance(0));

				long bal = balRaw == NativeMonero.LONG_ERR
						? lastBal : (lastBal = balRaw);
				long unl = unlRaw == NativeMonero.LONG_ERR
						? lastUnl : (lastUnl = unlRaw);

				lastHistoryFp = publishHistoryIfChanged(t, lastHistoryFp);
				if (synced) {
					publish(t, connectedStatus(t, XmrSyncState.SYNCED, wh, dh,
							bal, unl, checking));
					pace(t, checking ? syncingPollMs : pollIntervalMs);
				} else {
					XmrSyncState st = dh > 0 ? XmrSyncState.SYNCHRONIZING
							: XmrSyncState.CONNECTED;
					publish(t, connectedStatus(t, st, wh, dh, bal, unl,
							checking));
					pace(t, syncingPollMs);
				}
			}
		} catch (Throwable e) {
			publish(t, XmrSyncStatus.of(XmrSyncState.ERROR));
		} finally {
			try {
				t.session.pauseRefresh();
			} catch (Throwable ignored) {
			}
			if (active == t) active = null;
			Runnable r;
			while ((r = tasks.poll()) != null) {
				sessionExecutor.execute(r);
			}
		}
	}

	private void startBackgroundScan(Token t) {
		try {
			t.session.setAutoRefreshInterval(REFRESH_INTERVAL_MS);
			t.session.startRefresh();
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Wake the wallet's refresh thread for one incremental pass now. The thread
	 * only wakes on the disabled-to-enabled edge, so pause then start. If a scan
	 * is already running the flag change is harmless and no second scan starts.
	 */
	private void wakeRefresh(Token t) {
		try {
			t.session.pauseRefresh();
			t.session.setAutoRefreshInterval(REFRESH_INTERVAL_MS);
			t.session.startRefresh();
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Persist the wallet cache without racing the wallet's own refresh thread.
	 * That thread appends to the block-hash chain as it scans, and store()
	 * serializes the same chain to build the cache file; writing it while the
	 * chain is being appended reallocates it out from under the serializer, a
	 * use-after-free the device's memory tagging traps as a fatal fault.
	 * Pausing the refresh prevents any new scan from starting, and waiting for
	 * it to fall idle guarantees the in-flight scan has released the chain
	 * before the write. If the scan cannot be quiesced within the budget the
	 * store is skipped and retried on a later cycle rather than written into a
	 * mutating chain. The refresh is always resumed.
	 */
	private boolean persistCache(Token t) {
		try {
			t.session.pauseRefresh();
		} catch (Throwable ignored) {
		}
		boolean stored = false;
		try {
			if (t.session.waitRefreshIdle(STORE_QUIESCE_MS)) {
				stored = t.session.store("");
			}
		} catch (Throwable ignored) {
		} finally {
			try {
				t.session.startRefresh();
			} catch (Throwable ignored) {
			}
		}
		return stored;
	}

	/**
	 * Stops scanning on the failed node and tries each other node once, in
	 * order, returning the first that connects or -1 when all have been tried.
	 */
	private int failover(Token t) {
		try {
			t.session.stopRefresh();
		} catch (Throwable ignored) {
		}
		try {
			t.session.pauseRefresh();
		} catch (Throwable ignored) {
		}
		return connectAnyFrom(t, t.nodeIndex + 1);
	}

	/**
	 * Tries each node once starting at {@code start} (wrapping), returning the
	 * index of the first that connects, or -1 if a full pass over all nodes
	 * fails. Bounded to one attempt per node so it can never spin.
	 */
	private int connectAnyFrom(Token t, int start) {
		int n = t.nodes.size();
		if (n == 0) return -1;
		for (int k = 0; k < n; k++) {
			if (!live(t)) return -1;
			int i = ((start + k) % n + n) % n;
			XmrNode node = t.nodes.get(i);
			if (node.usesTor() && t.torSocksPort <= 0) {
				continue;
			}
			publish(t, connectingStatus(t, node));
			String proxy = node.usesTor() ? "127.0.0.1:" + t.torSocksPort : "";
			boolean ok;
			try {

				t.session.setRecoveringFromSeed(true);
				ok = t.session.init(node.address(), proxy, node.trusted);
			} catch (Throwable e) {
				ok = false;
			}
			int conn = ok ? safeConn(t) : 0;
			if (ok && conn == 1) {
				return i;
			}
		}
		return -1;
	}

	private int safeConn(Token t) {
		try {
			return t.session.connectionStatus();
		} catch (Throwable e) {
			return 0;
		}
	}

	private int safeStatus(Token t) {
		try {
			return t.session.status();
		} catch (Throwable e) {
			return 0;
		}
	}

	private interface LongOp {
		long run();
	}

	private static long safeLong(LongOp op) {
		try {
			return op.run();
		} catch (Throwable e) {
			return 0;
		}
	}

	private void pace(Token t, long totalMs) {
		long waited = 0;
		while (waited < totalMs && live(t) && !t.refreshRequested
				&& !t.taskPending) {
			long chunk = Math.min(200, totalMs - waited);
			try {
				Thread.sleep(chunk);
			} catch (InterruptedException e) {
				t.cancelled = true;
				Thread.currentThread().interrupt();
				return;
			}
			waited += chunk;
		}
	}

	@Nullable
	private String labelFor(Token t) {
		int i = t.nodeIndex;
		return (i >= 0 && i < t.nodes.size()) ? t.nodes.get(i).shortLabel() : null;
	}

	/**
	 * Stable identity of the daemon the active session is connected to
	 * ({@link XmrNode#endpointId()}), or null when not connected. Intended for
	 * records that must name the exact endpoint later, never a list index.
	 */
	@Nullable
	public String currentNodeEndpointId() {
		Token t = active;
		if (t == null || t.cancelled) return null;
		int i = t.nodeIndex;
		return (i >= 0 && i < t.nodes.size()) ? t.nodes.get(i).endpointId() : null;
	}

	/** The node the sync loop is currently connected to, or null. */
	@Nullable
	public XmrNode currentNode() {
		Token t = active;
		if (t == null || t.cancelled) return null;
		int i = t.nodeIndex;
		return (i >= 0 && i < t.nodes.size()) ? t.nodes.get(i) : null;
	}

	private XmrSyncStatus connectedStatus(Token t, XmrSyncState state, long wh,
			long dh, long bal, long unl, boolean checking) {
		return new XmrSyncStatus(state, wh, dh, bal, unl, labelFor(t), null,
				checking);
	}

	private XmrSyncStatus connectingStatus(Token t, XmrNode n) {
		return new XmrSyncStatus(XmrSyncState.CONNECTING, 0, 0, 0, 0,
				n.shortLabel(), null);
	}
}
