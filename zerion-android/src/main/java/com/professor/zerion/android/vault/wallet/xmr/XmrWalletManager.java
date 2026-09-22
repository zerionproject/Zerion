package com.professor.zerion.android.vault.wallet.xmr;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.professor.zerion.android.vault.ui.Event;
import com.professor.zerion.android.vault.wallet.WalletCoin;
import com.professor.zerion.android.vault.wallet.WalletRecord;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.RandomAccessFile;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * XMR wallet lifecycle: create, import/restore, open (with the mandatory
 * second wallet authentication), read-only synchronization over Tor, receive,
 * rename, rescan, delete, and session invalidation. No send. The ZVault seed
 * (via {@link WalletStore}) is the authoritative recovery source. The native
 * wallet keeps a persistent, encrypted scan cache per wallet under the
 * no-backup directory so synchronization resumes from the last scanned height;
 * its keys file is fund-sensitive encrypted key material protected by a
 * high-entropy file password held only inside the vault, and any cache that
 * is missing, corrupt, foreign or version-incompatible is discarded and rebuilt
 * from the seed. See docs/XMR_ARCHITECTURE.md.
 *
 * The XMR session is bound to the vault lock generation: any vault lock,
 * auto-lock, process death, or wallet switch invalidates it, stops scanning,
 * flushes the cache, closes the native handle and clears buffers. Deletion
 * removes the wallet's secrets from the vault before its encrypted files are
 * overwritten and removed.
 */
@NotNullByDefault
public final class XmrWalletManager {

	private static final String RENAME_KEY = "_rename";

	private final File noBackupBase;
	private final VaultGate vaultManager;
	private final XmrStore walletStore;
	private final MoneroEngine engine;

	private final java.util.concurrent.Executor cryptoExecutor;
	private final java.util.concurrent.Executor sessionExecutor;

	private final XmrSyncManager syncManager;
	private volatile int torSocksPort = -1;
	private volatile List<XmrNode> syncNodes =
			XmrNodeSelector.failoverOrder(null, new ArrayList<>(), true, null);

	@Nullable
	private volatile MoneroEngine.Session openSession;
	@Nullable
	private volatile MoneroEngine.Session spendSession;
	@Nullable
	private volatile File openWorkDir;
	@Nullable
	private volatile String openWalletId;
	private volatile long sessionEpoch = -1;

	private final MutableLiveData<List<WalletRecord>> wallets =
			new MutableLiveData<>();
	private final MutableLiveData<Event<String>> sessionOpened =
			new MutableLiveData<>();
	private final MutableLiveData<Event<XmrError>> error =
			new MutableLiveData<>();
	private final MutableLiveData<Event<String>> seedReveal =
			new MutableLiveData<>();
	private final MutableLiveData<Event<String>> walletDeleted =
			new MutableLiveData<>();
	private final MutableLiveData<Boolean> busy = new MutableLiveData<>();
	private final MutableLiveData<XmrSyncStatus> syncStatus =
			new MutableLiveData<>();
	private final MutableLiveData<Event<XmrReceiveAddress>> receiveAddress =
			new MutableLiveData<>();
	private final MutableLiveData<List<XmrReceiveAddress>> receiveList =
			new MutableLiveData<>();
	private final MutableLiveData<List<XmrTxInfo>> history =
			new MutableLiveData<>();
	private final MutableLiveData<XmrPrice.Rates> xmrRates =
			new MutableLiveData<>();

	private static final int RECEIVE_POOL_AHEAD = 20;

	private final java.util.concurrent.atomic.AtomicBoolean historyPending =
			new java.util.concurrent.atomic.AtomicBoolean(false);

	private final java.util.concurrent.atomic.AtomicBoolean sweptResidue =
			new java.util.concurrent.atomic.AtomicBoolean(false);

	private final java.util.concurrent.atomic.AtomicReference<String> exclusiveOp =
			new java.util.concurrent.atomic.AtomicReference<>();

	/**
	 * Exclusive wallet operations (delete, rename, rescan, and later the send
	 * flow) hold this while they run. An open attempted meanwhile is rejected
	 * with {@link XmrError#BUSY} instead of racing the operation for the
	 * session. Single-holder, compare-and-set, released in a finally.
	 */
	boolean beginExclusive(String name) {
		return exclusiveOp.compareAndSet(null, name);
	}

	void endExclusive() {
		exclusiveOp.set(null);
	}

	public boolean isExclusiveBusy() {
		return exclusiveOp.get() != null;
	}

	public XmrWalletManager(Context context, VaultGate vaultManager,
			XmrStore walletStore, MoneroEngine engine) {
		this(new File(context.getApplicationContext().getNoBackupFilesDir(),
				"xmr"), vaultManager, walletStore, engine,
				XmrSessionThreads.singleThread("XmrCrypto"),
				XmrSessionThreads.singleThread("XmrSession"));
	}

	XmrWalletManager(File noBackupBase, VaultGate vaultManager,
			XmrStore walletStore, MoneroEngine engine,
			java.util.concurrent.Executor cryptoExecutor) {
		this(noBackupBase, vaultManager, walletStore, engine, cryptoExecutor,
				cryptoExecutor);
	}

	XmrWalletManager(File noBackupBase, VaultGate vaultManager,
			XmrStore walletStore, MoneroEngine engine,
			java.util.concurrent.Executor cryptoExecutor,
			java.util.concurrent.Executor sessionExecutor) {
		this.noBackupBase = noBackupBase;
		this.vaultManager = vaultManager;
		this.walletStore = walletStore;
		this.engine = engine;
		this.cryptoExecutor = cryptoExecutor;
		this.sessionExecutor = sessionExecutor;
		this.syncManager = new XmrSyncManager(sessionExecutor,
				this::syncOwnsCurrent, this::publishStatusWithOverlay, 10000);
		this.syncManager.setHistorySink(this::publishHistoryWithOverlay);
		this.journalStore = new XmrSpendJournalStore(walletStore);
		vaultManager.addLockListener(this::invalidateSession);
	}

	private final XmrSpendJournalStore journalStore;

	private static final long LOOKUP_TIMEOUT_MS = 15_000;
	private volatile java.util.function.LongSupplier wallClock =
			System::currentTimeMillis;
	private volatile long relayExpiryMs = XmrSpendReconciler.RELAY_EXPIRY_MS;
	private final MutableLiveData<Event<String>> spendReleased =
			new MutableLiveData<>();

	void setWallClock(java.util.function.LongSupplier clock) {
		this.wallClock = clock;
	}

	void setRelayExpiryMs(long ms) {
		this.relayExpiryMs = ms;
	}

	/** Fires with the wallet id when an unresolved send was released. */
	public LiveData<Event<String>> getSpendReleased() {
		return spendReleased;
	}

	/**
	 * Positive-only reconciliation against the daemon the given session is
	 * connected to: looks every journal txid up in the daemon's pool and
	 * chain and in the session's own outgoing history, and clears the journal
	 * only when every txid is positively accepted. A MISSED or errored answer
	 * resolves nothing. Runs on the session executor; returns the lookups so
	 * a caller may apply the release rule to the same evidence. This is the
	 * production reconciliation path: it runs right after every relay on the
	 * spend session, after every view-session open, and on every user refresh,
	 * so an uncertain relay that did reach the network can never leave the
	 * wallet quarantined for longer than the next connection.
	 */
	private List<XmrTxLookup> reconcileSpendJournalFromDaemon(String walletId,
			@Nullable MoneroEngine.Session s) {
		if (s == null) return java.util.Collections.emptyList();
		XmrSpendJournalStore.Status st = journalStore.read(walletId);
		if (st.kind != XmrSpendJournalStore.Kind.PRESENT || st.journal == null) {
			return java.util.Collections.emptyList();
		}
		List<XmrTxLookup> lookups;
		try {
			lookups = s.lookupTxs(st.journal.txids(), LOOKUP_TIMEOUT_MS);
		} catch (Throwable e) {
			lookups = java.util.Collections.emptyList();
		}
		try {
			reconcileSpendJournal(walletId, XmrSpendReconciler.acceptedFrom(
					lookups, outgoingHistoryTxids(s)));
		} catch (Exception ignored) {
		}
		return lookups;
	}

	/** Reconcile the open wallet's journal against its current daemon. */
	private void reconcileIfQuarantined(String walletId) {
		if (!walletId.equals(openWalletId()) || !isSessionValid()) return;
		if (!journalStore.isQuarantined(walletId)) return;
		reconcileSpendJournalFromDaemon(walletId, openSession);
	}

	/**
	 * Password-gated release of an unresolved send after the expiry window.
	 * Re-authenticates with the wallet password, then on the session thread
	 * re-runs the daemon reconciliation on the open session: positive
	 * evidence resolves the journal normally; otherwise the release rule of
	 * {@link XmrSpendReconciler#releasable} must hold on that fresh evidence
	 * (journal older than the expiry, every txid MISSED by an answering
	 * daemon, none in history), and only then the journal is cleared and the
	 * pending reservation dropped. Anything else reports
	 * {@link XmrError#RELAY_UNRESOLVED} and changes nothing. Never automatic,
	 * never a re-broadcast.
	 */
	public void releaseUnresolvedSend(String walletId, char[] walletPassword) {
		busy.postValue(true);
		cryptoExecutor.execute(() -> {
			char[] seed = null;
			try {
				if (walletPassword.length == 0) {
					fail(XmrError.EMPTY_PASSWORD);
					return;
				}
				try {
					seed = walletStore.loadMnemonicChars(walletId, walletPassword);
				} catch (Throwable t) {
					fail(isWrongPassword(t) ? XmrError.WRONG_PASSWORD
							: XmrError.CORRUPTED_ITEM);
					return;
				}
				syncManager.submit(() -> releaseOnSession(walletId));
			} finally {
				if (seed != null) java.util.Arrays.fill(seed, '\0');
				java.util.Arrays.fill(walletPassword, '\0');
				busy.postValue(false);
			}
		});
	}

	private void releaseOnSession(String walletId) {
		MoneroEngine.Session s = openSession;
		if (s == null || !walletId.equals(openWalletId()) || !isSessionValid()) {
			fail(XmrError.SESSION_INVALIDATED);
			return;
		}
		XmrSpendJournalStore.Status st = journalStore.read(walletId);
		if (st.kind == XmrSpendJournalStore.Kind.ABSENT) {
			spendReleased.postValue(new Event<>(walletId));
			return;
		}
		if (st.kind == XmrSpendJournalStore.Kind.CORRUPTED
				|| st.journal == null) {
			fail(XmrError.JOURNAL_CORRUPTED);
			return;
		}
		List<XmrTxLookup> lookups = reconcileSpendJournalFromDaemon(walletId, s);
		if (!journalStore.isQuarantined(walletId)) {
			spendReleased.postValue(new Event<>(walletId));
			return;
		}
		Set<String> history = outgoingHistoryTxids(s);
		if (!XmrSpendReconciler.releasable(st.journal, lookups, history,
				wallClock.getAsLong(), relayExpiryMs)) {
			fail(XmrError.RELAY_UNRESOLVED);
			return;
		}
		try {
			journalStore.clear(walletId);
		} catch (Exception e) {
			fail(XmrError.STORAGE_COMMIT_FAILED);
			return;
		}
		dropPendingSends(walletId, new java.util.HashSet<>(st.journal.txids()));
		spendReleased.postValue(new Event<>(walletId));
	}

	/** Remove the outstanding-send records whose txids all belong to the
	 *  released journal, dropping their reservation; others are untouched. */
	private void dropPendingSends(String walletId, Set<String> released) {
		List<XmrPendingSend> cur = readPendingSends(walletId);
		if (cur.isEmpty()) return;
		List<XmrPendingSend> next = new java.util.ArrayList<>(cur.size());
		boolean changed = false;
		for (XmrPendingSend p : cur) {
			if (p.txids.length > 0 && released.containsAll(
					java.util.Arrays.asList(p.txids))) {
				changed = true;
			} else {
				next.add(p);
			}
		}
		if (changed) persistPendingSends(walletId, next);
	}

	/**
	 * True when an unresolved spend journal makes this wallet spend-quarantined:
	 * a present or an unreadable/corrupt journal both block a new spend. Reading
	 * requires no session and survives lock, restart and process death because
	 * the journal is durable on disk.
	 */
	public boolean isSpendQuarantined(String walletId) {
		return journalStore.isQuarantined(walletId);
	}

	/** The full journal status for the reconciliation authority. */
	public XmrSpendJournalStore.Status spendJournalStatus(String walletId) {
		return journalStore.read(walletId);
	}

	/**
	 * Reject a new spend before any transaction construction while the wallet is
	 * quarantined. The send flow calls this before preparing; it is the
	 * manager/core enforcement point, never a UI button state.
	 */
	public void requireSpendAllowed(String walletId)
			throws XmrError.XmrException {
		if (journalStore.isQuarantined(walletId)) {
			throw new XmrError.XmrException(XmrError.SPEND_QUARANTINED);
		}
	}

	/**
	 * Durably record a relay attempt before it may run. Returns only after the
	 * journal is committed; a failure throws and the caller must not relay.
	 */
	public void writeSpendJournalDurably(XmrSpendJournal journal)
			throws XmrError.XmrException {
		journalStore.writeDurably(journal);
	}

	/**
	 * Run the conservative reconciliation over a wallet's journal given the
	 * txids for which positive evidence was gathered (pool, mined, or outgoing
	 * history). Clears the quarantine only when every journal txid is positively
	 * accepted or definitively rejected; a corrupt journal is never cleared
	 * automatically, and a merely-absent (MISSED) txid never resolves anything.
	 * This is the reconciliation authority: clearing happens here, never from UI.
	 */
	/**
	 * Positive-only runtime reconciliation driven by fresh sync history. When the
	 * open wallet is quarantined by an unresolved spend journal and its own
	 * outgoing history now contains the journalled transactions, that is
	 * definitive positive evidence the relay reached the network, so the journal
	 * clears. History never proves rejection, so a transaction the wallet has not
	 * yet seen leaves the quarantine in place; only positive evidence clears it.
	 */
	private void maybeReconcileFromHistory(List<XmrTxInfo> published) {
		String id = openWalletId();
		if (id == null) return;
		try {
			if (!journalStore.isQuarantined(id)) return;
		} catch (Throwable ignored) {
			return;
		}
		Set<String> outgoing = new java.util.HashSet<>();
		for (XmrTxInfo tx : published) {
			if (tx.direction == XmrTxInfo.Direction.OUT) outgoing.add(tx.txid);
		}
		if (outgoing.isEmpty()) return;
		final String wid = id;

		syncManager.submit(() -> {
			try {
				if (!wid.equals(openWalletId())) return;
				reconcileSpendJournal(wid, XmrSpendReconciler.acceptedFrom(
						java.util.Collections.emptyList(), outgoing));
			} catch (Exception ignored) {
			}
		});
	}

	private volatile List<XmrPendingSend> pendingSends =
			java.util.Collections.emptyList();

	/** Sum of the exact debit still to reserve for the wallet: the total debit of
	 *  each outstanding send whose spend has not yet been incorporated into the
	 *  background wallet's own balance (not converged). A converged send reserves
	 *  nothing, so its debit is never subtracted twice. */
	private long reservedFor(@Nullable String walletId) {
		if (walletId == null) return 0;
		long sum = 0;
		for (XmrPendingSend p : pendingSends) {
			if (p.walletId.equals(walletId)) sum += p.reservationDebit();
		}
		return sum;
	}

	/**
	 * Publish sync status with the pending-send reservation applied: while any
	 * relayed-but-not-yet-canonical outgoing transaction is outstanding for the
	 * open wallet, the sum of their exact total debits is subtracted from the
	 * displayed balance and spendable balance so already-spent funds are never
	 * shown as still available. The reservation for a send is dropped the moment
	 * canonical history observes its txids. The canonical wallet2 balance is
	 * never modified; this is a display-only overlay tied to exact durable
	 * transactions.
	 */
	private void publishStatusWithOverlay(XmrSyncStatus s) {
		correctClockHeightOnce(openWalletId, s.daemonHeight);
		long reserved = reservedFor(openWalletId);
		if (reserved > 0) {
			long bal = Math.max(s.balanceAtomic - reserved, 0);
			long unl = Math.max(s.unlockedAtomic - reserved, 0);
			s = new XmrSyncStatus(s.state, s.walletHeight, s.daemonHeight,
					bal, unl, s.nodeLabel, s.error, s.checking);
		}
		syncStatus.postValue(s);
	}

	/**
	 * Publish history with the pending outgoing overlay merged in, then
	 * reconcile. For each relayed txid canonical history has not yet observed, a
	 * synthetic pending outgoing row (exact amount and fee, zero confirmations)
	 * is shown so every outstanding send appears immediately and is tappable.
	 * When all of a send's txids appear in canonical history that send's overlay
	 * rows and its balance reservation are dropped and canonical rows take over,
	 * so a row is never duplicated. Independent sends resolve independently.
	 */
	private void publishHistoryWithOverlay(List<XmrTxInfo> canonical) {
		history.postValue(mergeOutgoingHistory(openWalletId, pendingSends,
				canonical));
		maybeReconcileFromHistory(canonical);
	}

	/**
	 * Merge Zerion's durable outgoing records with the canonical wallet2 history
	 * into one deduplicated, newest-first list. A view-only wallet cannot itself
	 * reconstruct an outgoing transaction, so for every txid Zerion authored a
	 * send for, its exact locally known outgoing row is shown (permanently, never
	 * dropped) and enriched with the canonical height/confirmations/mined state
	 * when the chain has since observed that txid; the canonical row for that
	 * txid, which a view wallet would otherwise surface only as the change coming
	 * back in, is suppressed so the send appears once, as an outgoing row.
	 */
	static List<XmrTxInfo> mergeOutgoingHistory(@Nullable String walletId,
			List<XmrPendingSend> records, List<XmrTxInfo> canonical) {
		List<XmrPendingSend> owning = new java.util.ArrayList<>();
		java.util.Set<String> owned = new java.util.HashSet<>();
		if (walletId != null) {
			for (XmrPendingSend p : records) {
				if (walletId.equals(p.walletId)) {
					owning.add(p);
					java.util.Collections.addAll(owned, p.txids);
				}
			}
		}
		if (owning.isEmpty()) return canonical;

		java.util.Map<String, XmrTxInfo> canonicalOwned = new java.util.HashMap<>();
		List<XmrTxInfo> out = new java.util.ArrayList<>(canonical.size());
		for (XmrTxInfo tx : canonical) {
			if (owned.contains(tx.txid)) {
				canonicalOwned.put(tx.txid, tx);
			} else {
				out.add(tx);
			}
		}
		for (XmrPendingSend p : owning) {
			for (String id : p.txids) {
				out.add(p.historyRow(id, canonicalOwned.get(id)));
			}
		}
		java.util.Collections.sort(out, (a, b) -> {
			int t = Long.compare(b.timestamp, a.timestamp);
			return t != 0 ? t : Long.compare(b.height, a.height);
		});
		return out;
	}

	/** The durable outstanding-send records of a wallet; for tests only. */
	List<XmrPendingSend> pendingSendsFor(String walletId) {
		return readPendingSends(walletId);
	}

	private List<XmrPendingSend> readPendingSends(String walletId) {
		try {
			org.json.JSONObject xmr = settingsObject().optJSONObject("xmr");
			if (xmr != null) {
				org.json.JSONObject w = xmr.optJSONObject(walletId);
				if (w != null) {
					List<XmrPendingSend> list = new java.util.ArrayList<>();
					for (XmrPendingSend p : XmrPendingSend.listFromJson(
							w.optString("ps", ""))) {
						if (p.walletId.equals(walletId)) list.add(p);
					}
					return list;
				}
			}
		} catch (Throwable ignored) {
		}
		return new java.util.ArrayList<>();
	}

	/** Persist the outstanding-send set and, only on a successful write, adopt
	 *  it in memory so a failed write can never clear a live reservation. */
	private void persistPendingSends(String walletId,
			List<XmrPendingSend> list) {
		try {
			synchronized (walletStore.settingsMonitor()) {
				org.json.JSONObject o = settingsObject();
				org.json.JSONObject xmr = o.optJSONObject("xmr");
				if (xmr == null) xmr = new org.json.JSONObject();
				org.json.JSONObject w = xmr.optJSONObject(walletId);
				if (w == null) w = new org.json.JSONObject();
				if (list.isEmpty()) w.remove("ps");
				else w.put("ps", XmrPendingSend.listToJson(list));
				xmr.put(walletId, w);
				o.put("xmr", xmr);
				walletStore.writeSettings(o.toString());
			}
			pendingSends = list;
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Persist the exact relayed transaction as a pending outgoing overlay so the
	 * send is shown immediately and its funds reserved until canonical history
	 * catches up. A multi-tx send records every txid and the summed exact debit,
	 * so a partial relay stays fully reserved and truthful. A new send is added
	 * to the outstanding set, never replacing an earlier unresolved one.
	 */
	private void recordPendingSend(@Nullable XmrSendSnapshot snap,
			long changeAtomic, boolean uncertain, boolean converged) {
		if (snap == null) return;
		String wid = openWalletId;
		if (wid == null) return;
		List<String> txids = snap.txids();
		if (txids.isEmpty()) return;
		long reservedInput = snap.totalDebitAtomic()
				+ (changeAtomic > 0 ? changeAtomic : 0);
		List<XmrPendingSend> next = new java.util.ArrayList<>(pendingSends);
		next.add(new XmrPendingSend(wid, txids.toArray(new String[0]),
				snap.amountAtomic(), snap.feeAtomic(), snap.totalDebitAtomic(),
				reservedInput, System.currentTimeMillis(), uncertain, converged));
		persistPendingSends(wid, next);
	}

	/**
	 * Write the spend wallet's post-relay state back into the view-only
	 * background cache. The spend wallet marked its spent outputs on relay
	 * (wallet2 commit_tx -> set_spent) and holds the custom background key loaded
	 * from its own keys file, so a store() updates w.background
	 * (wallet2::store -> store_background_cache); the reopened background wallet's
	 * balance then canonically excludes those outputs. The spend session runs no
	 * background refresh thread (open does a single blocking refresh), so the
	 * store cannot race the block-hash chain. Returns whether it succeeded; on
	 * failure the send stays reserved (not converged) rather than under-counted.
	 */
	private boolean propagateSpendStateToBackground() {
		MoneroEngine.Session sp = spendSession;
		if (sp == null) return false;
		try {
			return sp.store("");
		} catch (Throwable e) {
			return false;
		}
	}

	/**
	 * Reconcile the view-only balance against spends of the same seed made
	 * outside Zerion, so an externally-spent output can never be shown as
	 * spendable. A view-only wallet cannot compute key images, so an output a
	 * different wallet holding the same seed has spent stays unspent in the view
	 * cache. wallet2 resolves this locally: opening the spend wallet runs
	 * process_background_cache_on_open, which replays the transactions the view
	 * wallet already scanned - carried in the background cache as plausible
	 * spends - with the spend key present, so real key images resolve and the
	 * externally-spent outputs are marked spent; the following store regenerates
	 * w.background carrying those spent flags, so the reopened view excludes
	 * them. No daemon is contacted, and the spend key is in memory only for this
	 * transient step gated by the main-file password derived from the wallet
	 * password, so Store-1 holds: vault-tier access alone still cannot open the
	 * spend wallet. Called with no view session open, on the session executor.
	 * Best-effort - on any failure the cache is left unchanged and the displayed
	 * balance is never increased. The same store also incorporates any of
	 * Zerion's own sends the view has since scanned, so every pending send the
	 * reopened spend wallet authoritatively reports as outgoing is converged
	 * here, releasing its reservation exactly once so the same debit is never
	 * subtracted twice. That same authoritative outgoing set also positively
	 * resolves a stale spend-quarantine, so an uncertain relay that in fact
	 * reached the network can never leave the wallet permanently unable to send.
	 */
	/**
	 * The same reconciliation on a spend session that is already open, which
	 * is the point in the shipped flow where the wallet password is
	 * available: every send opens the spend wallet, whose open replays the
	 * background cache with the spend key and marks externally spent outputs.
	 * The pending sends and the spend journal converge on what that wallet
	 * reports, and the view is rebuilt from the spend wallet's state once the
	 * send ends, whether it relayed, failed or was cancelled.
	 */
	private void reconcileExternalSpendsOn(String walletId,
			MoneroEngine.Session spend) {
		try {
			java.util.Set<String> spent = outgoingHistoryTxids(spend);
			convergeObservedSends(walletId, spent);
			try {
				reconcileSpendJournal(walletId, XmrSpendReconciler.acceptedFrom(
						java.util.Collections.emptyList(), spent));
			} catch (Exception ignored) {
			}
			viewRebuildFromSpend = true;
		} catch (Throwable ignored) {
		}
	}

	/** Set once a spend session has reconciled; cleared when the view is rebuilt. */
	private volatile boolean viewRebuildFromSpend = false;

	/**
	 * Ends a send that did not converge: when the spend session reconciled
	 * external spends, its state is written to the background cache and the
	 * view session is reopened from it, so the balance excludes what another
	 * wallet with the same seed has spent; otherwise sync simply resumes.
	 */
	private void finishSendView(String walletId) {
		boolean rebuild = viewRebuildFromSpend;
		viewRebuildFromSpend = false;
		if (rebuild) rebuild = propagateSpendStateToBackground();
		closeSpendSession();
		endExclusive();
		if (!rebuild) {
			rearmSyncIfIdle();
			return;
		}
		final long epoch = sessionEpoch;
		MoneroEngine.Session bg = openSession;
		openSession = null;
		if (bg != null) {
			try {
				bg.close();
			} catch (Throwable ignored) {
			}
		}
		if (epoch < 0 || !vaultManager.isUnlocked()
				|| epoch != vaultManager.getLockGeneration()) {
			return;
		}
		try {
			activateBackgroundSession(walletId, epoch);
		} catch (Throwable reopenFailed) {
		}
	}

	private void reconcileExternalSpends(String walletId, char[] walletPassword) {
		if (walletCv(walletId) < WALLET_V2) return;
		byte[] salt = loadKekSalt(walletId);
		if (salt == null) return;
		char[] mainPw = XmrWalletKek.deriveMainFilePassword(walletPassword, salt);
		MoneroEngine.Session spend = null;
		try {
			File dir = liveDir(walletId);
			spend = engine.open(
					new File(dir, "w").getAbsolutePath(), mainPw);
			if (spend == null || spend.status() != 0
					|| spend.isBackgroundWallet()) {
				return;
			}
			if (!spend.store("")) {
				return;
			}
			java.util.Set<String> spent = outgoingHistoryTxids(spend);
			convergeObservedSends(walletId, spent);
			try {
				reconcileSpendJournal(walletId, XmrSpendReconciler.acceptedFrom(
						java.util.Collections.emptyList(), spent));
			} catch (Exception ignored) {
			}
		} catch (Throwable ignored) {
		} finally {
			if (spend != null) {
				try {
					spend.close();
				} catch (Throwable ignored) {
				}
			}
			java.util.Arrays.fill(mainPw, '\0');
			java.util.Arrays.fill(salt, (byte) 0);
		}
	}

	/**
	 * Mark converged every not-yet-converged pending send for the wallet whose
	 * whole txid set the spend wallet reports as a real outgoing transaction,
	 * meaning its spend has just been incorporated into the background cache.
	 * Operates on the durable record so it survives the reopen. It releases the
	 * reservation exactly for those sends, never for one the spend wallet has not
	 * yet observed and never for an external spend (which carries no pending
	 * record); a send whose own relay-time convergence failed is released here
	 * once its spend genuinely lands, so the debit is subtracted once, not twice.
	 */
	private void convergeObservedSends(String walletId, Set<String> spentTxids) {
		List<XmrPendingSend> cur = readPendingSends(walletId);
		List<XmrPendingSend> next = convergeReflected(cur, spentTxids);
		if (next != cur) persistPendingSends(walletId, next);
	}

	/**
	 * Pure rule for {@link #convergeObservedSends}: return the records with every
	 * not-yet-converged send whose whole txid set appears in {@code spentTxids}
	 * marked converged, or the same list unchanged when nothing matches. A send
	 * with no txids, an already-converged send and any txid the spend wallet has
	 * not reported are left untouched, so a reservation is released only for a
	 * spend the cache now genuinely reflects and never twice.
	 */
	static List<XmrPendingSend> convergeReflected(List<XmrPendingSend> cur,
			Set<String> spentTxids) {
		if (cur.isEmpty() || spentTxids.isEmpty()) return cur;
		List<XmrPendingSend> next = new java.util.ArrayList<>(cur.size());
		boolean changed = false;
		for (XmrPendingSend p : cur) {
			if (!p.converged && p.txids.length > 0
					&& spentTxids.containsAll(
							java.util.Arrays.asList(p.txids))) {
				next.add(p.asConverged());
				changed = true;
			} else {
				next.add(p);
			}
		}
		return changed ? next : cur;
	}

	public XmrSpendReconciler.Outcome reconcileSpendJournal(String walletId,
			Set<String> acceptedTxids) throws Exception {
		XmrSpendJournalStore.Status st = journalStore.read(walletId);
		if (st.kind == XmrSpendJournalStore.Kind.ABSENT) {
			return XmrSpendReconciler.Outcome.RESOLVED;
		}
		if (st.kind == XmrSpendJournalStore.Kind.CORRUPTED
				|| st.journal == null) {
			return XmrSpendReconciler.Outcome.REMAIN_QUARANTINED;
		}
		XmrSpendReconciler.Outcome outcome =
				XmrSpendReconciler.decide(st.journal, acceptedTxids);
		if (outcome == XmrSpendReconciler.Outcome.RESOLVED) {
			journalStore.clear(walletId);
		}
		return outcome;
	}

	private static final long SEND_REFRESH_IDLE_TIMEOUT_MS = 5000;

	/**
	 * Bound on how long a reviewed-but-unconfirmed send may hold the spend
	 * session, the signed transaction and the exclusive slot. A review that the
	 * user never answers (the screen was re-created, the app went to the
	 * background, the process kept running) previously kept the spend key in
	 * memory and every other XMR wallet blocked until the vault locked.
	 */
	static final long SPEND_SESSION_TTL_MS = 180_000L;
	private volatile long spendSessionTtlMs = SPEND_SESSION_TTL_MS;
	private volatile long reviewReadyAtMs = -1L;
	private final java.util.concurrent.ScheduledExecutorService sendWatchdog =
			java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
				Thread t = new Thread(r, "XmrSendWatchdog");
				t.setDaemon(true);
				return t;
			});

	void setSpendSessionTtlMs(long ttlMs) {
		this.spendSessionTtlMs = ttlMs;
	}

	/**
	 * Cancel a send that has sat at review for longer than the TTL. Runs the
	 * ordinary cancel path, so the native transaction is freed, the
	 * authorization killed, the spend session closed and the slot released on
	 * the session executor. A flow that has moved past review (authorizing or
	 * relaying) is never interrupted here; a flow that already ended is a no-op.
	 */
	void expireStaleSendFlow() {
		XmrSendFlow flow = sendFlow;
		long at = reviewReadyAtMs;
		if (flow == null || at < 0) return;
		if (flow.state() != XmrSendFlow.State.REVIEW_READY) return;
		if (sendClock.nowMonotonicMs() - at < spendSessionTtlMs) return;
		cancelSend();
	}

	private void armSendWatchdog() {
		reviewReadyAtMs = sendClock.nowMonotonicMs();
		try {
			sendWatchdog.schedule(this::expireStaleSendFlow,
					spendSessionTtlMs, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (RuntimeException ignored) {
		}
	}

	@Nullable
	private volatile XmrSendFlow sendFlow;
	private final MutableLiveData<XmrSendUiState> sendState =
			new MutableLiveData<>();
	private XmrSendGate.MonotonicClock sendClock =
			android.os.SystemClock::elapsedRealtime;

	void setSendClock(XmrSendGate.MonotonicClock clock) {
		this.sendClock = clock;
	}

	public LiveData<XmrSendUiState> getSendState() {
		return sendState;
	}

	/** Address validity for immediate UI feedback, decided by Monero's parser
	 *  (the send flow re-checks it before constructing anything). */
	public boolean isValidXmrAddress(String address) {
		return engine.addressKind(address) != MoneroEngine.AddressKind.INVALID;
	}

	/**
	 * Begin the one send: validate the wallet, take the single exclusive slot,
	 * construct the flow bound to the live session on the session thread, quiesce
	 * refresh, sign, and post the reviewed snapshot. A quarantined wallet is
	 * rejected before anything else. Exactly one flow can be active at a time.
	 */
	public void prepareSend(String walletId, String walletLabel,
			String destination, long amountAtomic, int priority,
			char[] walletPassword) {
		busy.postValue(true);
		sendState.postValue(XmrSendUiState.preparing());
		syncManager.submit(() -> {
			try {
				if (journalStore.isQuarantined(walletId)) {
					sendState.postValue(XmrSendUiState.quarantined());
					return;
				}
				if (!walletId.equals(openWalletId) || !isSessionValid()) {
					sendState.postValue(
							XmrSendUiState.failed(XmrError.SESSION_INVALIDATED));
					return;
				}
				if (!beginExclusive("send")) {
					sendState.postValue(XmrSendUiState.failed(XmrError.BUSY));
					return;
				}
				final String relayEndpointId =
						syncManager.currentNodeEndpointId();
				final XmrNode relayNode = syncManager.currentNode();
				syncManager.stop();
				MoneroEngine.Session bg = openSession;
				if (bg != null) {
					try {
						bg.waitRefreshIdle(SEND_REFRESH_IDLE_TIMEOUT_MS);
						bg.store(new File(liveDir(walletId), "w.background")
								.getAbsolutePath());
					} catch (Throwable ignored) {
					}
				}
				boolean keepOpen = false;
				MoneroEngine.Session spend = null;
				try {
					if (relayNode == null) {
						sendState.postValue(XmrSendUiState.failed(
								XmrError.SESSION_INVALIDATED));
						return;
					}
					spend = openSpendSession(walletId, walletPassword,
							relayNode);
					final MoneroEngine.Session sp = spend;
					spendSession = sp;
					reconcileExternalSpendsOn(walletId, sp);
					XmrSendGate gate = new XmrSendGate(walletStore, sendGuard(),
							sendClock);
					XmrSendFlow flow = new XmrSendFlow(engine, sp, 0, walletId,
							gate, journalStore, sendGuard(),
							() -> relayEndpointId,
							() -> outgoingHistoryTxids(sp),
							SEND_REFRESH_IDLE_TIMEOUT_MS);
					sendFlow = flow;
					sendState.postValue(XmrSendUiState.preparing());
					int pri = priority < 0 ? 0 : (priority > 4 ? 4 : priority);
					flow.prepare(destination, amountAtomic, pri,
							sha256Bytes(sp.address(0, 0)));
					XmrSendSnapshot snap = flow.snapshot();
					if (snap == null) throw new XmrError.XmrException(
							XmrError.UNKNOWN);
					keepOpen = true;
					armSendWatchdog();
					sendState.postValue(XmrSendUiState.review(
							reviewFrom(snap, walletLabel)));
				} catch (XmrError.XmrException e) {
					sendState.postValue(XmrSendUiState.failed(e.error));
				} catch (Throwable t) {
					sendState.postValue(XmrSendUiState.failed(XmrError.UNKNOWN));
				} finally {
					if (!keepOpen) {
						clearSendFlow();
						closeSpendSession();
						endExclusive();
						rearmSyncIfIdle();
					}
				}
			} finally {
				java.util.Arrays.fill(walletPassword, '\0');
				busy.postValue(false);
			}
		});
	}

	/** Close and drop the transient spend-capable session, if any. */
	private void closeSpendSession() {
		MoneroEngine.Session sp = spendSession;
		spendSession = null;
		if (sp != null) {
			try {
				sp.close();
			} catch (Throwable ignored) {
			}
		}
	}

	/**
	 * Stage B: the fresh post-review authorization and relay. After the user has
	 * seen the exact immutable reviewed transaction, a fresh wallet password
	 * mints the single-use authorization bound to that reviewed snapshot,
	 * ownership, spend session, prepared native transaction and lock generation,
	 * which is then consumed once in the same serialized relay operation. A wrong
	 * password leaves the flow at review for a retry and does not relay. A
	 * terminal relay result releases the exclusive slot, closes the transient
	 * spend session, and resumes the view-only sync. Never relays twice.
	 */
	public void confirmSend(char[] walletPassword) {
		busy.postValue(true);
		syncManager.submit(() -> {
			XmrSendFlow flow = sendFlow;
			boolean terminal = false;
			final boolean[] converged = {false};
			try {
				if (flow == null) {
					sendState.postValue(
							XmrSendUiState.failed(XmrError.SESSION_INVALIDATED));
					terminal = true;
					return;
				}
				sendState.postValue(XmrSendUiState.authenticating());
				try {
					flow.authorize(walletPassword);
				} catch (XmrError.XmrException auth) {
					error.postValue(new Event<>(auth.error));
					XmrSendSnapshot snap = flow.snapshot();
					if (snap != null) {
						sendState.postValue(XmrSendUiState.review(
								reviewFrom(snap, "")));
					}
					return;
				}
				final long changeAtomic = flow.changeAtomic();
				sendState.postValue(XmrSendUiState.relaying());
				XmrSendFlow.RelayResult result = flow.confirmAndRelay();
				terminal = true;
				XmrSendSnapshot relayed = flow.snapshot();
				if (relayed != null && (result == XmrSendFlow.RelayResult.SUCCESS
						|| result == XmrSendFlow.RelayResult.RELAY_UNCERTAIN)) {
					reconcileSpendJournalFromDaemon(relayed.walletId(),
							spendSession);
				}
				switch (result) {
					case SUCCESS: {
						XmrSendSnapshot snap = flow.snapshot();
						converged[0] = convergeAfterRelay(snap, changeAtomic, false);
						sendState.postValue(XmrSendUiState.success(
								snap == null ? java.util.Collections.emptyList()
										: snap.txids()));
						break;
					}
					case RELAY_UNCERTAIN: {
						converged[0] = convergeAfterRelay(flow.snapshot(), changeAtomic,
								true);
						sendState.postValue(XmrSendUiState.relayUncertain());
						break;
					}
					default:
						sendState.postValue(
								XmrSendUiState.failed(XmrError.UNKNOWN));
						break;
				}
			} catch (Throwable t) {
				sendState.postValue(XmrSendUiState.failed(XmrError.UNKNOWN));
				terminal = true;
			} finally {
				java.util.Arrays.fill(walletPassword, '\0');
				if (terminal) {
					clearSendFlow();
					if (converged[0]) {
						viewRebuildFromSpend = false;
						closeSpendSession();
						endExclusive();
					} else {
						finishSendView(flow.walletId());
					}
				}
				busy.postValue(false);
			}
		});
	}

	/** Cancel the send: dispose the transaction, invalidate the authorization,
	 *  release the slot and resume sync. */
	public void cancelSend() {
		syncManager.submit(() -> {
			XmrSendFlow flow = sendFlow;
			if (flow != null) flow.cancel();
			String id = flow != null ? flow.walletId() : openWalletId;
			clearSendFlow();
			sendState.postValue(XmrSendUiState.cancelled());
			if (id != null) finishSendView(id);
			else {
				closeSpendSession();
				endExclusive();
				rearmSyncIfIdle();
			}
		});
	}

	private void clearSendFlow() {
		sendFlow = null;
		reviewReadyAtMs = -1L;
	}

	/**
	 * Tear down any active send on the session executor: free the native
	 * transaction, drop the flow, release the exclusive slot and close the
	 * transient spend session. Shared by the vault-lock path and the explicit
	 * session close, so an orphaned flow can never outlive either.
	 */
	private void teardownSendFlowOnExecutor() {
		XmrSendFlow flow = sendFlow;
		if (flow != null) {
			flow.disposeOnExecutor();
			clearSendFlow();
			endExclusive();
		}
		closeSpendSession();
	}

	private void invalidateSendFlow() {
		XmrSendFlow flow = sendFlow;
		if (flow != null) flow.invalidate();
	}

	private XmrSendUiState.Review reviewFrom(XmrSendSnapshot s,
			String walletLabel) {
		return new XmrSendUiState.Review(s.amountAtomic(), s.destinationExact(),
				s.destinationKind(), s.feeAtomic(), s.totalDebitAtomic(),
				s.txCount(), walletLabel);
	}

	private Set<String> outgoingHistoryTxids(MoneroEngine.Session s) {
		Set<String> out = new java.util.HashSet<>();
		try {
			for (XmrTxInfo t : s.history()) {
				if (t.direction == XmrTxInfo.Direction.OUT && !t.failed) {
					out.add(t.txid);
				}
			}
		} catch (Throwable ignored) {
		}
		return out;
	}

	private static byte[] sha256Bytes(String s) {
		try {
			return java.security.MessageDigest.getInstance("SHA-256").digest(
					s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		} catch (Exception e) {
			return new byte[32];
		}
	}

	/**
	 * User refresh: one incremental pass from the current scanned height. If a
	 * sync loop owns the session the request is coalesced into it (a wake of the
	 * wallet's own refresh thread, never a second scan); if the wallet is
	 * offline the connection is re-established through the normal failover.
	 */
	public void refreshNow() {
		String id = openWalletId();
		if (id != null) {
			syncManager.submit(() -> reconcileIfQuarantined(id));
		}
		if (syncManager.isActive()) {
			syncManager.requestRefresh();
		} else {
			retrySync();
		}
	}

	private final Object pendingSeedLock = new Object();
	private static final long PENDING_SEED_TTL_MS = 10 * 60_000L;
	@Nullable
	private char[] pendingSeed;
	@Nullable
	private String pendingSeedWallet;
	private long pendingSeedAt;

	private void stashPendingSeed(String walletId, char[] seed) {
		synchronized (pendingSeedLock) {
			wipePendingSeedLocked();
			pendingSeed = seed.clone();
			pendingSeedWallet = walletId;
			pendingSeedAt = System.currentTimeMillis();
		}
	}

	private void wipePendingSeed() {
		synchronized (pendingSeedLock) {
			wipePendingSeedLocked();
		}
	}

	private void wipePendingSeedLocked() {
		if (pendingSeed != null) java.util.Arrays.fill(pendingSeed, '\0');
		pendingSeed = null;
		pendingSeedWallet = null;
	}

	/**
	 * Hand the just-revealed recovery phrase to the phrase screen exactly once.
	 * The caller owns the returned copy and must wipe it. Returns null if there
	 * is no pending phrase for this wallet (after a lock, process death, or the
	 * short hand-off window), in which case the phrase must be re-requested
	 * with authentication.
	 */
	@Nullable
	public char[] takePendingSeed(String walletId) {
		synchronized (pendingSeedLock) {
			if (pendingSeed == null || !walletId.equals(pendingSeedWallet)) {
				return null;
			}
			if (System.currentTimeMillis() - pendingSeedAt > PENDING_SEED_TTL_MS) {
				wipePendingSeedLocked();
				return null;
			}
			char[] out = pendingSeed.clone();
			wipePendingSeedLocked();
			return out;
		}
	}

	private final MutableLiveData<Boolean> backupVerified =
			new MutableLiveData<>();

	public LiveData<Boolean> getBackupVerified() {
		return backupVerified;
	}

	public void loadBackupState(String walletId) {
		cryptoExecutor.execute(() -> backupVerified.postValue(
				readBackupVerified(walletId)));
	}

	public void setBackupVerified(String walletId, boolean verified) {
		cryptoExecutor.execute(() -> {
			try {
				synchronized (walletStore.settingsMonitor()) {
					org.json.JSONObject o = settingsObject();
					org.json.JSONObject xmr = o.optJSONObject("xmr");
					if (xmr == null) xmr = new org.json.JSONObject();
					org.json.JSONObject w = xmr.optJSONObject(walletId);
					if (w == null) w = new org.json.JSONObject();
					w.put("bv", verified);
					xmr.put(walletId, w);
					o.put("xmr", xmr);
					walletStore.writeSettings(o.toString());
				}
			} catch (Throwable ignored) {
			}
			backupVerified.postValue(readBackupVerified(walletId));
		});
	}

	private boolean readBackupVerified(String walletId) {
		try {
			org.json.JSONObject xmr = settingsObject().optJSONObject("xmr");
			if (xmr != null) {
				org.json.JSONObject w = xmr.optJSONObject(walletId);
				if (w != null) return w.optBoolean("bv", false);
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	public LiveData<XmrPrice.Rates> getXmrRates() {
		return xmrRates;
	}

	/**
	 * Fetch the current XMR price over Tor and publish it for the send screen's
	 * fiat readout. Runs off the UI thread; a failed or empty fetch leaves the
	 * last published or cached price in place rather than clearing it. This is
	 * display-only and never affects the atomic amount that is signed and sent.
	 */
	public void loadPrice() {
		cryptoExecutor.execute(() -> {
			try {
				int port = torSocksPort;
				if (port <= 0) return;
				String tag = com.professor.zerion.android.vault.wallet.btc
						.TorIsolation.price("xmr");
				XmrPrice.Rates r = XmrPrice.fetch(port, tag);
				if (r != null && !r.isEmpty()) {
					persistXmrRates(r);
					xmrRates.postValue(r);
				}
			} catch (Throwable ignored) {
			}
		});
	}

	/**
	 * Publish the last persisted XMR price at once so the send screen shows a
	 * figure before a fresh network fetch returns.
	 */
	private static final long PRICE_TTL_MS = 20L * 60L * 1000L;

	public void loadCachedPrice() {
		cryptoExecutor.execute(() -> {
			try {
				org.json.JSONObject o = settingsObject();
				String cached = o.optString("xmrPriceRates", "");
				long at = o.optLong("xmrPriceAt", 0);

				if (!cached.isEmpty()
						&& System.currentTimeMillis() - at <= PRICE_TTL_MS) {
					XmrPrice.Rates r = XmrPrice.Rates.fromJson(cached);
					if (!r.isEmpty()) xmrRates.postValue(r);
				}
			} catch (Throwable ignored) {
			}
		});
	}

	private void persistXmrRates(XmrPrice.Rates r) {
		try {
			synchronized (walletStore.settingsMonitor()) {
				org.json.JSONObject o = settingsObject();
				o.put("xmrPriceRates", r.toJson());
				o.put("xmrPriceAt", System.currentTimeMillis());
				walletStore.writeSettings(o.toString());
			}
		} catch (Throwable ignored) {
		}
	}

	private boolean syncOwnsCurrent(String walletId, long epoch) {
		return isSessionValid() && walletId.equals(openWalletId)
				&& epoch == sessionEpoch;
	}

	public LiveData<XmrSyncStatus> getSyncStatus() {
		return syncStatus;
	}

	public void setTorSocksPort(int port) {
		this.torSocksPort = port;
	}

	public void setSyncNodes(List<XmrNode> nodes) {
		this.syncNodes = nodes;
	}

	/** Load the saved node preference and apply it as the failover order. */
	public void reloadNodeConfig() {
		cryptoExecutor.execute(() -> {
			try {
				this.syncNodes = XmrNodeConfig.load(walletStore).toFailoverList();
			} catch (Throwable ignored) {
			}
		});
	}

	public XmrNodeConfig currentNodeConfig() {
		return XmrNodeConfig.load(walletStore);
	}

	public void saveNodeConfig(XmrNodeConfig config) {
		cryptoExecutor.execute(() -> {
			try {
				config.save(walletStore);
				this.syncNodes = config.toFailoverList();
			} catch (Throwable ignored) {
			}
		});
	}

	public LiveData<List<WalletRecord>> getWallets() {
		return wallets;
	}

	public LiveData<Event<String>> getSessionOpened() {
		return sessionOpened;
	}

	public LiveData<Event<XmrError>> getError() {
		return error;
	}

	public LiveData<Event<String>> getSeedReveal() {
		return seedReveal;
	}

	public LiveData<Event<String>> getWalletDeleted() {
		return walletDeleted;
	}

	public LiveData<Boolean> getBusy() {
		return busy;
	}

	public LiveData<Event<XmrReceiveAddress>> getReceiveAddress() {
		return receiveAddress;
	}

	public LiveData<List<XmrReceiveAddress>> getReceiveList() {
		return receiveList;
	}

	public LiveData<List<XmrTxInfo>> getHistory() {
		return history;
	}

	public void loadHistory(String walletId) {
		if (!historyPending.compareAndSet(false, true)) {
			return;
		}
		syncManager.submit(() -> {
			try {
				MoneroEngine.Session s = openSession;
				if (s == null || !walletId.equals(openWalletId)
						|| !isSessionValid()) {
					return;
				}
				try {
					publishHistoryWithOverlay(s.history());
				} catch (Throwable ignored) {
				}
			} finally {
				historyPending.set(false);
			}
		});
	}

	/**
	 * Re-run sync on the currently open wallet after an OFFLINE state. Restarts
	 * the failover from the first node; the background scan is the sole refresher
	 * so this cannot create an overlapping native scan.
	 */
	public void retrySync() {
		sessionExecutor.execute(() -> {
			MoneroEngine.Session s = openSession;
			String id = openWalletId;
			if (s == null || id == null || !isSessionValid()) {
				return;
			}
			syncManager.stop();
			syncManager.start(id, sessionEpoch, s, syncNodes, torSocksPort);
		});
	}

	/**
	 * Pre-generate and cache a pool of subaddress strings at open time.
	 * Subaddresses are deterministic from the seed and index, so caching them in
	 * the vault lets Receive hand out a fresh address with no session or network
	 * call. Runs on the session executor at open, before sync starts.
	 * Best-effort: a failure here never blocks opening the wallet.
	 */
	private void ensureReceivePool(MoneroEngine.Session session, String walletId) {
		try {
			XmrSubaddressLedger ledger = new XmrSubaddressLedger(
					new XmrReceiveJsonStore(walletStore, walletId));
			int target = ledger.issuedCount() + RECEIVE_POOL_AHEAD;
			growSubaddresses(session, target);
			java.util.Map<Integer, String> pool = new java.util.HashMap<>();
			for (int i = 0; i <= target; i++) {
				String a = session.address(0, i);
				if (a != null && !a.isEmpty()) pool.put(i, a);
			}
			if (!pool.isEmpty()) ledger.cacheAddresses(pool);
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Grow the subaddress table to at least {@code target} indices. Stops as
	 * soon as an add does not advance the count, so a session that does not
	 * generate new subaddresses (a Monero background/view-only wallet returns a
	 * fixed count) can never spin this loop; the caller falls back to whatever
	 * indices already exist. Never runs unbounded native work on the open path.
	 */
	private void growSubaddresses(MoneroEngine.Session session, int target) {
		long count = session.numSubaddresses(0);
		if (count < 0) return;
		while (count <= target) {
			session.addSubaddress(0, "");
			long next = session.numSubaddresses(0);
			if (next <= count) return;
			count = next;
		}
	}

	/**
	 * Issue a fresh receive subaddress. The index is reserved crash-safely
	 * (persisted before it is shown); the address string is read from the cached
	 * pool so this works offline, off the network executor. If the pool is
	 * exhausted and the live session is available, one more is generated.
	 */
	public void newReceiveAddress(String walletId) {
		cryptoExecutor.execute(() -> {
			try {
				XmrSubaddressLedger ledger = new XmrSubaddressLedger(
						new XmrReceiveJsonStore(walletStore, walletId));
				int index = ledger.reserveNext(System.currentTimeMillis());
				String addr = ledger.cachedAddress(index);
				if (addr == null) {
					addr = generatePoolExtension(walletId, ledger, index);
				}
				if (addr == null) {
					error.postValue(new Event<>(XmrError.UNKNOWN));
					return;
				}
				receiveAddress.postValue(new Event<>(new XmrReceiveAddress(
						walletId, index, addr, ledger.label(index),
						ledger.issuedDate(index), null)));
			} catch (Throwable e) {
				error.postValue(new Event<>(XmrError.STORAGE_COMMIT_FAILED));
			}
		});
	}

	@Nullable
	private String generatePoolExtension(String walletId,
			XmrSubaddressLedger ledger, int index) {
		java.util.concurrent.FutureTask<String> task =
				new java.util.concurrent.FutureTask<>(() -> {
					MoneroEngine.Session s = openSession;
					if (s == null || !walletId.equals(openWalletId)) return null;
					growSubaddresses(s, index);
					String addr = s.address(0, index);
					if (addr != null && !addr.isEmpty()) {
						ledger.cacheAddress(index, addr);
					}
					return addr;
				});
		syncManager.submit(task);
		try {
			return task.get(30, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Throwable e) {
			task.cancel(false);
			return null;
		}
	}

	public void loadReceiveList(String walletId) {
		cryptoExecutor.execute(() -> {
			try {
				XmrSubaddressLedger ledger = new XmrSubaddressLedger(
						new XmrReceiveJsonStore(walletStore, walletId));
				List<XmrReceiveAddress> out = new ArrayList<>();
				for (int index : ledger.issuedIndices()) {
					String addr = ledger.cachedAddress(index);
					if (addr == null) continue;
					out.add(new XmrReceiveAddress(walletId, index, addr,
							ledger.label(index), ledger.issuedDate(index), null));
				}
				receiveList.postValue(out);
			} catch (Throwable ignored) {
			}
		});
	}

	public void setReceiveLabel(String walletId, int index,
			@Nullable String label) {
		cryptoExecutor.execute(() -> {
			try {
				new XmrSubaddressLedger(new XmrReceiveJsonStore(walletStore,
						walletId)).setLabel(index, label);
				loadReceiveList(walletId);
			} catch (Throwable ignored) {
			}
		});
	}

	private boolean isXmrWallet(String walletId) {
		try {
			for (WalletRecord w : walletStore.listWallets()) {
				if (w.id.equals(walletId)) return w.coin == WalletCoin.XMR;
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	public void loadWallets() {
		cryptoExecutor.execute(() -> {
			try {
				sweepStaleWorkDirs();
				reconcileRename();
				postXmrWallets();
			} catch (Throwable e) {
				error.postValue(new Event<>(XmrError.UNKNOWN));
			}
		});
	}

	/**
	 * Publish the Monero wallet list from persisted state, filtered to the
	 * persisted coin. Called synchronously at the commit point of a delete or
	 * rename (on the session thread) so observers see the updated list before
	 * the completion event, rather than after the best-effort file cleanup that
	 * follows.
	 */
	private void postXmrWallets() throws Exception {
		List<WalletRecord> all = walletStore.listWallets();
		List<WalletRecord> xmr = new ArrayList<>();
		for (WalletRecord w : all) {
			if (w.coin != WalletCoin.XMR) continue;
			String display = readDisplayName(w.id, w.name);
			xmr.add(display.equals(w.name) ? w
					: new WalletRecord(w.id, w.coin, display,
							w.createdTimestamp, w.hasPassword));
		}
		wallets.postValue(xmr);
	}

	public void createWallet(String name, char[] walletPassword) {
		busy.postValue(true);
		cryptoExecutor.execute(() -> {
			if (!engine.isAvailable()) {
				fail(XmrError.NATIVE_UNAVAILABLE, walletPassword, null);
				return;
			}
			if (walletPassword.length == 0) {
				fail(XmrError.EMPTY_PASSWORD, walletPassword, null);
				return;
			}
			File dir = newWorkDir();
			char[] filePw = randomFilePassword();
			MoneroEngine.Session s = null;
			char[] seed = null;
			try {
				s = engine.create(new File(dir, "w").getAbsolutePath(), filePw,
						"English");
				if (s == null || s.status() != 0) {
					fail(XmrError.NATIVE_CREATE_FAILED, walletPassword, dir);
					return;
				}
				seed = s.seed(new char[0]);
				if (seed.length == 0) {
					fail(XmrError.NATIVE_CREATE_FAILED, walletPassword, dir);
					return;
				}
				String id;
				try {
					id = walletStore.createWallet(WalletCoin.XMR, name, seed,
							walletPassword);
				} catch (Throwable commit) {
					fail(XmrError.STORAGE_COMMIT_FAILED, walletPassword, dir);
					return;
				}
				long height = XmrBirthday.estimateHeight(
						System.currentTimeMillis());
				try {
					persistRestoreHeight(id, height);
					persistHeightFromClock(id, true);
				} catch (Throwable ignored) {
				}
				File live = liveDir(id);
				try {
					shred(live);
					live.mkdirs();
					establishV2(id, live, seed, height, walletPassword);
				} catch (Throwable buildFail) {
					shred(live);
				}
				stashPendingSeed(id, seed);
				loadWallets();
				seedReveal.postValue(new Event<>(id));
			} catch (Throwable e) {
				fail(XmrError.NATIVE_CREATE_FAILED, walletPassword, dir);
				return;
			} finally {
				if (s != null) s.close();
				if (seed != null) java.util.Arrays.fill(seed, '\0');
				java.util.Arrays.fill(filePw, '\0');
				java.util.Arrays.fill(walletPassword, '\0');
				shred(dir);
				busy.postValue(false);
			}
		});
	}

	public void importWallet(String name, char[] seedWords, long restoreHeight,
			char[] walletPassword) {
		busy.postValue(true);
		cryptoExecutor.execute(() -> {
			if (!engine.isAvailable()) {
				fail(XmrError.NATIVE_UNAVAILABLE, walletPassword, null);
				java.util.Arrays.fill(seedWords, '\0');
				return;
			}
			if (walletPassword.length == 0) {
				fail(XmrError.EMPTY_PASSWORD, walletPassword, null);
				java.util.Arrays.fill(seedWords, '\0');
				return;
			}
			File dir = newWorkDir();
			char[] filePw = randomFilePassword();
			MoneroEngine.Session s = null;
			try {
				s = engine.restore(new File(dir, "w").getAbsolutePath(), filePw,
						seedWords, Math.max(restoreHeight, 0), new char[0]);
				if (s == null || s.status() != 0) {
					fail(XmrError.MALFORMED_SEED, walletPassword, dir);
					return;
				}
				String id;
				try {
					id = walletStore.createWallet(WalletCoin.XMR, name, seedWords,
							walletPassword);
				} catch (Throwable commit) {
					fail(XmrError.STORAGE_COMMIT_FAILED, walletPassword, dir);
					return;
				}
				long h = Math.max(restoreHeight, 0);
				try {
					persistRestoreHeight(id, h);
				} catch (Throwable ignored) {
				}
				File live = liveDir(id);
				try {
					shred(live);
					live.mkdirs();
					establishV2(id, live, seedWords, h, walletPassword);
				} catch (Throwable buildFail) {
					shred(live);
				}
				loadWallets();
			} catch (Throwable e) {
				fail(XmrError.MALFORMED_SEED, walletPassword, dir);
				return;
			} finally {
				if (s != null) s.close();
				java.util.Arrays.fill(filePw, '\0');
				java.util.Arrays.fill(seedWords, '\0');
				java.util.Arrays.fill(walletPassword, '\0');
				shred(dir);
				busy.postValue(false);
			}
		});
	}

	/**
	 * Open a wallet for viewing with only the vault unlocked and no wallet
	 * password: the Monero view-only background session. It can sync, show the
	 * balance and history, and allocate receive addresses, but it holds no spend
	 * key and can never sign. A wallet that still needs the password (legacy or
	 * not yet built) is reported with {@link XmrError#WALLET_NEEDS_PASSWORD} so
	 * the surface can ask for it.
	 */
	public void openWalletForView(String walletId) {
		busy.postValue(true);
		syncManager.stop();
		sessionExecutor.execute(() -> {
			try {
				if (!engine.isAvailable()) {
					fail(XmrError.NATIVE_UNAVAILABLE);
					return;
				}
				if (!vaultManager.isUnlocked()) {
					fail(XmrError.SESSION_INVALIDATED);
					return;
				}
				long epoch = vaultManager.getLockGeneration();
				if (isExclusiveBusy()) {
					fail(XmrError.BUSY);
					return;
				}
				if (!isXmrWallet(walletId)) {
					fail(XmrError.CORRUPTED_ITEM);
					return;
				}
				if (needsPasswordToOpen(walletId)) {
					fail(XmrError.WALLET_NEEDS_PASSWORD);
					return;
				}
				if (walletId.equals(openWalletId) && isSessionValid()) {
					sessionOpened.postValue(new Event<>(walletId));
					return;
				}
				closeCurrentSession();
				activateBackgroundSession(walletId, epoch);
			} catch (XmrError.XmrException xe) {
				fail(xe.error);
			} catch (Throwable e) {
				fail(XmrError.NATIVE_OPEN_FAILED);
			} finally {
				busy.postValue(false);
				rearmSyncIfIdle();
			}
		});
	}

	/**
	 * Open a wallet with the wallet password. The password decrypts the seed as
	 * a fresh authentication, then the second-layer wallet is built (for a new
	 * wallet whose files are missing) or the legacy wallet is migrated in place,
	 * after which the normal runtime state is the same view-only background
	 * session. The password never opens the runtime session and is not retained.
	 */
	public void openWallet(String walletId, char[] walletPassword) {
		busy.postValue(true);
		syncManager.stop();
		sessionExecutor.execute(() -> {
			char[] seed = null;
			try {
				if (!engine.isAvailable()) {
					fail(XmrError.NATIVE_UNAVAILABLE, walletPassword, null);
					return;
				}
				if (walletPassword.length == 0) {
					fail(XmrError.EMPTY_PASSWORD, walletPassword, null);
					return;
				}
				if (!vaultManager.isUnlocked()) {
					fail(XmrError.SESSION_INVALIDATED, walletPassword, null);
					return;
				}
				long epoch = vaultManager.getLockGeneration();
				if (isExclusiveBusy()) {
					fail(XmrError.BUSY, walletPassword, null);
					return;
				}
				if (!isXmrWallet(walletId)) {
					fail(XmrError.CORRUPTED_ITEM, walletPassword, null);
					return;
				}
				try {
					seed = walletStore.loadMnemonicChars(walletId, walletPassword);
				} catch (Throwable loadFailed) {
					boolean wrongPassword =
							loadFailed instanceof SecurityException
							|| loadFailed instanceof javax.crypto.AEADBadTagException
							|| isWrongPassword(loadFailed);
					if (!wrongPassword) {
						fail(XmrError.CORRUPTED_ITEM, walletPassword, null);
						return;
					}
					fail(XmrError.WRONG_PASSWORD, walletPassword, null);
					return;
				}
				try {
					ensureV2(walletId, seed, walletPassword);
				} catch (XmrError.XmrException xe) {
					fail(xe.error, walletPassword, null);
					return;
				}
				if (!vaultManager.isUnlocked()
						|| epoch != vaultManager.getLockGeneration()) {
					fail(XmrError.SESSION_INVALIDATED, walletPassword, null);
					return;
				}
				if (walletId.equals(openWalletId) && isSessionValid()) {
					sessionOpened.postValue(new Event<>(walletId));
					return;
				}
				closeCurrentSession();
				reconcileExternalSpends(walletId, walletPassword);
				try {
					activateBackgroundSession(walletId, epoch);
				} catch (XmrError.XmrException activateFailed) {
					File live = liveDir(walletId);
					shred(live);
					live.mkdirs();
					establishV2(walletId, live, seed,
							readRestoreHeight(walletId), walletPassword);
					activateBackgroundSession(walletId, epoch);
				}
			} catch (XmrError.XmrException xe) {
				fail(xe.error, walletPassword, null);
			} catch (Throwable e) {
				fail(XmrError.NATIVE_OPEN_FAILED, walletPassword, null);
			} finally {
				if (seed != null) java.util.Arrays.fill(seed, '\0');
				java.util.Arrays.fill(walletPassword, '\0');
				busy.postValue(false);
				rearmSyncIfIdle();
			}
		});
	}

	/**
	 * Open the view-only background session and make it the runtime session:
	 * start the sync loop, seed the receive pool, publish history. Fails closed
	 * if the opened wallet is not a background wallet or the vault locked or the
	 * generation changed during the open, so a spend-capable handle is never
	 * activated as the runtime session.
	 */
	private void activateBackgroundSession(String walletId, long epoch)
			throws XmrError.XmrException {
		File dir = liveDir(walletId);
		char[] bgPw = loadBackgroundPassword(walletId);
		if (bgPw == null) {
			throw new XmrError.XmrException(XmrError.NATIVE_OPEN_FAILED);
		}
		MoneroEngine.Session s;
		try {
			s = engine.open(new File(dir, "w.background").getAbsolutePath(),
					bgPw);
		} finally {
			java.util.Arrays.fill(bgPw, '\0');
		}
		if (s == null || s.status() != 0 || !s.isBackgroundWallet()) {
			if (s != null) s.close();
			throw new XmrError.XmrException(XmrError.NATIVE_OPEN_FAILED);
		}
		if (!identityMatchesStored(s, walletId)) {
			s.close();
			throw new XmrError.XmrException(XmrError.CORRUPTED_ITEM);
		}
		if (!vaultManager.isUnlocked()
				|| epoch != vaultManager.getLockGeneration()) {
			s.close();
			throw new XmrError.XmrException(XmrError.SESSION_INVALIDATED);
		}
		stripPlaintext(dir);
		openSession = s;
		openWorkDir = dir;
		openWalletId = walletId;
		sessionEpoch = epoch;

		pendingSends = readPendingSends(walletId);
		ensureReceivePool(s, walletId);
		sessionOpened.postValue(new Event<>(walletId));
		try {
			publishHistoryWithOverlay(s.history());
		} catch (Throwable ignored) {
		}
		syncManager.start(walletId, epoch, s, syncNodes, torSocksPort);
		syncManager.submit(() -> reconcileIfQuarantined(walletId));
	}

	/**
	 * Ensure the wallet is a built, verified V2 (two-layer) wallet, rebuilding
	 * the derivable Monero cache from the seed when the files are missing and
	 * migrating a legacy vault-tier wallet in place. The seed is the only
	 * fund-critical secret and is never deleted here, so a crash at any point
	 * simply re-runs this from the seed; the atomic V2 commit (which drops any
	 * legacy vault-tier credential) happens only after the spend/view split is
	 * verified, so the model never silently downgrades.
	 */
	private void ensureV2(String walletId, char[] seed, char[] walletPassword)
			throws XmrError.XmrException {
		File dir = liveDir(walletId);
		if (walletCv(walletId) >= WALLET_V2 && backgroundFilesPresent(dir)) {
			return;
		}
		try {
			shred(dir);
			dir.mkdirs();
			long height = readRestoreHeight(walletId);
			establishV2(walletId, dir, seed, height, walletPassword);
		} catch (XmrError.XmrException xe) {
			throw xe;
		} catch (Throwable t) {
			throw new XmrError.XmrException(XmrError.NATIVE_OPEN_FAILED, t);
		}
	}

	/**
	 * openWallet stops the running sync loop up front so the open is not queued
	 * behind it. If that open then fails (wrong password, stale id, engine
	 * unavailable) the previously open wallet is still valid but unobserved, so
	 * its loop is started again here. A no-op when a loop is already active.
	 */
	private void rearmSyncIfIdle() {
		MoneroEngine.Session s = openSession;
		String id = openWalletId;
		if (s == null || id == null || !isSessionValid()
				|| syncManager.isActive()) {
			return;
		}
		syncManager.start(id, sessionEpoch, s, syncNodes, torSocksPort);
	}

	/**
	 * Converge the view-only background wallet's own balance with a just-relayed
	 * spend, so the spent funds can never reappear as spendable:
	 * <ol>
	 * <li>durably record the send with its balance reservation active;
	 * <li>close the running background session so its keys-file lock is released
	 *     (an open background wallet holds it, blocking the cache write);
	 * <li>write the spend wallet's post-relay state - its spent outputs marked by
	 *     wallet2 commit_tx - into the background cache (a store on the
	 *     CustomPassword main wallet updates w.background);
	 * <li>mark the send converged so its reservation is released, exactly once,
	 *     without ever double-subtracting;
	 * <li>reopen the background session from the converged cache so its in-memory
	 *     balance canonically excludes the spent outputs.
	 * </ol>
	 * On any failure the send stays reserved (not converged): the displayed
	 * balance is conservatively reduced and never shows the spent funds as
	 * spendable. An uncertain relay is never converged here: wallet2 marks
	 * outputs spent only after the daemon accepted the transaction, so on an
	 * uncertain commit the stored cache carries no spent flags and releasing
	 * the reservation would show funds that may be gone. The reservation is
	 * then held until the spend wallet itself observes the outgoing
	 * transaction (convergence on the password-gated open) or the user
	 * releases the unresolved send after the expiry window. Returns whether it
	 * converged (and thus already reopened sync).
	 */
	private boolean convergeAfterRelay(@Nullable XmrSendSnapshot snap,
			long changeAtomic, boolean uncertain) {
		final String id = openWalletId;
		final long epoch = sessionEpoch;
		recordPendingSend(snap, changeAtomic, uncertain, false);
		java.util.List<String> justSent = snap == null
				? java.util.Collections.emptyList() : snap.txids();
		MoneroEngine.Session bg = openSession;
		openSession = null;
		if (bg != null) {
			try {
				bg.close();
			} catch (Throwable ignored) {
			}
		}
		boolean ok = propagateSpendStateToBackground();
		if (ok && !uncertain && id != null) markSendConverged(id, justSent);
		if (id == null || epoch < 0 || !vaultManager.isUnlocked()
				|| epoch != vaultManager.getLockGeneration()) {
			return ok;
		}
		try {
			activateBackgroundSession(id, epoch);
		} catch (Throwable reopenFailed) {
		}
		return ok;
	}

	/**
	 * Re-activate the reservation of every pending send for the wallet, used
	 * after a rebuild/rescan discards the spent state from the view-only wallet:
	 * a rebuild must never make already-spent funds appear spendable again.
	 * Durable, so it survives across the reopen that follows the rescan.
	 */
	private void deconvergePendingSends(String walletId) {
		List<XmrPendingSend> cur = readPendingSends(walletId);
		if (cur.isEmpty()) return;
		List<XmrPendingSend> next = new java.util.ArrayList<>();
		boolean changed = false;
		for (XmrPendingSend p : cur) {
			if (p.converged) {
				next.add(new XmrPendingSend(p.walletId, p.txids, p.amountAtomic,
						p.feeAtomic, p.totalDebitAtomic, p.reservedInputAtomic,
						p.createdAtMs, p.uncertain, false));
				changed = true;
			} else {
				next.add(p);
			}
		}
		if (changed) persistPendingSends(walletId, next);
	}

	/**
	 * Mark converged ONLY the one send whose exact txid set was just written into
	 * the background cache, releasing its reservation. Other outstanding sends
	 * (e.g. an earlier one whose own store failed) keep their reservation, so
	 * converging a later send can never release an earlier still-unconverged one.
	 */
	private void markSendConverged(String walletId, List<String> txids) {
		if (txids.isEmpty()) return;
		java.util.Set<String> target = new java.util.HashSet<>(txids);
		List<XmrPendingSend> next = new java.util.ArrayList<>();
		boolean changed = false;
		for (XmrPendingSend p : pendingSends) {
			if (p.walletId.equals(walletId) && !p.converged
					&& target.size() == p.txids.length
					&& target.containsAll(java.util.Arrays.asList(p.txids))) {
				next.add(p.asConverged());
				changed = true;
			} else {
				next.add(p);
			}
		}
		if (changed) persistPendingSends(walletId, next);
	}

	/**
	 * Delete in cryptographic-erasure order. Fresh authentication first; then
	 * the sync loop is told to stop (a flag flip and an interrupt, never a
	 * wait); then the security-critical commit removes the wallet's settings
	 * entry (file password, identity, restore height, labels) and its seed
	 * record from the vault; the list is republished from persisted state and
	 * the completion event posted. Only after that does the session thread
	 * close the native handle and overwrite the now-undecryptable files. The
	 * commit therefore never waits for an in-flight block batch: closing the
	 * handle is not required to remove the secrets that make the files usable.
	 */
	public void deleteWallet(String walletId, char[] walletPassword) {
		deleteWallet(walletId, walletPassword, false);
	}

	/**
	 * Delete with an explicit acknowledgement that an unresolved relay may
	 * exist. Without the acknowledgement a quarantined wallet still refuses to
	 * delete, so the unresolved state cannot be discarded by accident; with it
	 * the user has been told the wallet is only recoverable from its recovery
	 * phrase and that the outcome of the last send must be checked from the
	 * restored wallet. A wallet is never left undeletable.
	 */
	public void deleteWallet(String walletId, char[] walletPassword,
			boolean acknowledgeUnresolvedSpend) {
		busy.postValue(true);
		cryptoExecutor.execute(() -> {
			if (walletPassword.length == 0) {
				fail(XmrError.EMPTY_PASSWORD, walletPassword, null);
				return;
			}
			if (!beginExclusive("delete")) {
				fail(XmrError.BUSY, walletPassword, null);
				return;
			}
			char[] seed = null;
			try {
				if (!acknowledgeUnresolvedSpend
						&& journalStore.isQuarantined(walletId)) {
					fail(XmrError.SPEND_QUARANTINED, walletPassword, null);
					return;
				}
				try {
					seed = walletStore.loadMnemonicChars(walletId, walletPassword);
				} catch (Throwable t) {
					fail(isWrongPassword(t) ? XmrError.WRONG_PASSWORD
							: XmrError.CORRUPTED_ITEM, walletPassword, null);
					return;
				}
				synchronized (pendingSeedLock) {
					if (walletId.equals(pendingSeedWallet)) wipePendingSeedLocked();
				}
				if (walletId.equals(openWalletId)) {
					syncManager.stop();
				}
				removeMetadata(walletId);
				walletStore.deleteWallet(walletId);
				try {
					walletStore.removeWalletSecret(walletId,
							BACKGROUND_PASSWORD_SECRET);
				} catch (Throwable ignored) {
				}
				try {
					postXmrWallets();
				} catch (Throwable ignored) {
				}
				walletDeleted.postValue(new Event<>(walletId));
				sessionExecutor.execute(() -> {
					if (walletId.equals(openWalletId)) {
						closeCurrentSession(false);
					}
					try {
						removeMetadata(walletId);
					} catch (Throwable ignored) {
					}
					try {
						journalStore.clear(walletId);
					} catch (Throwable ignored) {
					}
					try {
						shred(liveDir(walletId));
					} catch (Throwable ignored) {
					}
					sweptResidue.set(false);
					sweepStaleWorkDirs();
				});
			} catch (Throwable e) {
				error.postValue(new Event<>(XmrError.UNKNOWN));
			} finally {
				endExclusive();
				if (seed != null) java.util.Arrays.fill(seed, '\0');
				java.util.Arrays.fill(walletPassword, '\0');
				busy.postValue(false);
			}
		});
	}

	/**
	 * Rename a wallet by updating its mutable display name only. The wallet id is
	 * the immutable identity: the seed, keys, native files, spend journal, receive
	 * index, restore height and every other id-keyed piece of state are untouched,
	 * so a label change never re-encrypts the seed, migrates state, or changes the
	 * id. The wallet password is verified first, so a rename still requires
	 * authentication and proves ownership, but nothing cryptographic is resealed.
	 */
	public void renameWallet(String walletId, String newName,
			char[] walletPassword) {
		busy.postValue(true);
		cryptoExecutor.execute(() -> {
			try {
				if (walletPassword.length == 0) {
					fail(XmrError.EMPTY_PASSWORD, walletPassword, null);
					return;
				}
				try {
					char[] seed = walletStore.loadMnemonicChars(walletId,
							walletPassword);
					java.util.Arrays.fill(seed, '\0');
				} catch (Throwable t) {
					fail(isWrongPassword(t) ? XmrError.WRONG_PASSWORD
							: XmrError.CORRUPTED_ITEM, walletPassword, null);
					return;
				}
				try {
					persistDisplayName(walletId, newName);
				} catch (Throwable commit) {
					fail(XmrError.STORAGE_COMMIT_FAILED, walletPassword, null);
					return;
				}
				loadWallets();
			} catch (Throwable e) {
				error.postValue(new Event<>(XmrError.UNKNOWN));
			} finally {
				java.util.Arrays.fill(walletPassword, '\0');
				busy.postValue(false);
			}
		});
	}

	private void removeMetadata(String walletId) throws Exception {
		synchronized (walletStore.settingsMonitor()) {
			if (walletStore.readSettings() == null) return;
			org.json.JSONObject o = settingsObject();
			org.json.JSONObject xmr = o.optJSONObject("xmr");
			if (xmr != null && xmr.has(walletId)) {
				xmr.remove(walletId);
				o.put("xmr", xmr);
				walletStore.writeSettings(o.toString());
			}
		}
	}

	private void clearRenameJournal() throws Exception {
		synchronized (walletStore.settingsMonitor()) {
			org.json.JSONObject o = settingsObject();
			org.json.JSONObject xmr = o.optJSONObject("xmr");
			if (xmr != null && xmr.has(RENAME_KEY)) {
				xmr.remove(RENAME_KEY);
				o.put("xmr", xmr);
				walletStore.writeSettings(o.toString());
			}
		}
	}

	private void reconcileRename() {
		try {
			org.json.JSONObject xmr = settingsObject().optJSONObject("xmr");
			if (xmr == null) return;
			org.json.JSONObject rn = xmr.optJSONObject(RENAME_KEY);
			if (rn == null) return;
			String from = rn.optString("from", null);
			String to = rn.has("to") && !rn.isNull("to")
					? rn.optString("to", null) : null;
			if (from != null && to != null) {
				boolean fromExists = false;
				boolean toExists = false;
				for (WalletRecord w : walletStore.listWallets()) {
					if (w.id.equals(from)) fromExists = true;
					if (w.id.equals(to)) toExists = true;
				}
				if (fromExists && toExists) {
					walletStore.deleteWallet(from);
					try {
						walletStore.removeWalletSecret(from,
								BACKGROUND_PASSWORD_SECRET);
					} catch (Throwable ignored) {
					}
					removeMetadata(from);
				}
			}
			clearRenameJournal();
		} catch (Throwable ignored) {
		}
	}

	@Nullable
	private String displayNameOf(String walletId) {
		try {
			for (WalletRecord w : walletStore.listWallets()) {
				if (w.id.equals(walletId)) return w.name;
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	public void revealSeed(String walletId, char[] walletPassword) {
		cryptoExecutor.execute(() -> {
			if (walletPassword.length == 0) {
				fail(XmrError.EMPTY_PASSWORD, walletPassword, null);
				return;
			}
			char[] seed = null;
			try {
				try {
					seed = walletStore.loadMnemonicChars(walletId, walletPassword);
				} catch (Throwable t) {
					fail(isWrongPassword(t) ? XmrError.WRONG_PASSWORD
							: XmrError.CORRUPTED_ITEM, walletPassword, null);
					return;
				}
				stashPendingSeed(walletId, seed);
				seedReveal.postValue(new Event<>(walletId));
			} finally {
				if (seed != null) java.util.Arrays.fill(seed, '\0');
				java.util.Arrays.fill(walletPassword, '\0');
			}
		});
	}

	/**
	 * Advanced recovery: discard the persisted cache and rescan. A restore height
	 * of 0 is a full scan, a positive value scans from there, and a negative value
	 * leaves the wallet's existing restore height unchanged (rebuild from its own
	 * birthday/import height). Requires fresh wallet authentication. The ZVault
	 * seed is the authority; the cache is rebuilt from it on next open, so this
	 * can never lose funds. The wallet is closed here and reopened by the caller
	 * (which re-authenticates).
	 */
	public void rescan(String walletId, char[] walletPassword, long restoreHeight) {
		busy.postValue(true);
		cryptoExecutor.execute(() -> {
			if (walletPassword.length == 0) {
				fail(XmrError.EMPTY_PASSWORD, walletPassword, null);
				return;
			}
			if (!beginExclusive("rescan")) {
				fail(XmrError.BUSY, walletPassword, null);
				return;
			}
			char[] seed = null;
			try {
				try {
					seed = walletStore.loadMnemonicChars(walletId, walletPassword);
				} catch (Throwable t) {
					fail(isWrongPassword(t) ? XmrError.WRONG_PASSWORD
							: XmrError.CORRUPTED_ITEM, walletPassword, null);
					return;
				}
				final char[] seedForRebuild = seed;
				runSessionTeardown(() -> {
					if (walletId.equals(openWalletId)) {
						closeCurrentSession(false);
					}
					if (restoreHeight >= 0) {
						try {
							persistRestoreHeight(walletId, restoreHeight);
						} catch (Throwable ignored) {
						}
					}
					clearCache(walletId);

					deconvergePendingSends(walletId);
					File live = liveDir(walletId);
					live.mkdirs();
					try {
						establishV2(walletId, live, seedForRebuild,
								readRestoreHeight(walletId), walletPassword);
					} catch (Throwable rebuildFailed) {
						shred(live);
					}
					return null;
				});
			} catch (Throwable e) {
				error.postValue(new Event<>(XmrError.UNKNOWN));
			} finally {
				endExclusive();
				if (seed != null) java.util.Arrays.fill(seed, '\0');
				java.util.Arrays.fill(walletPassword, '\0');
				busy.postValue(false);
			}
		});
	}

	public boolean isSessionValid() {
		return openSession != null
				&& openWalletId != null
				&& vaultManager.isUnlocked()
				&& sessionEpoch == vaultManager.getLockGeneration();
	}

	@Nullable
	public String openWalletId() {
		return isSessionValid() ? openWalletId : null;
	}

	/**
	 * The session epoch (the lock generation captured at open) while the session
	 * is valid, else -1. Used to bind a send authorization to this exact
	 * session; a lock, wallet switch or reopen changes it.
	 */
	public long currentSessionEpoch() {
		return isSessionValid() ? sessionEpoch : -1;
	}

	/** The live vault lock generation, which increments on every vault lock. */
	public long currentLockGeneration() {
		return vaultManager.getLockGeneration();
	}

	/**
	 * A live view of this manager's session generation and identity for the send
	 * gate. It reads the same fields the manager uses for its own validity
	 * checks, so a send authorization and the exclusive send window bind to the
	 * one session the singleton manager owns.
	 */
	public XmrSendGate.SendGuard sendGuard() {
		return new XmrSendGate.SendGuard() {
			@Override
			public long sessionEpoch() {
				return currentSessionEpoch();
			}

			@Override
			public long lockGeneration() {
				return currentLockGeneration();
			}

			@Override
			public boolean sessionValid() {
				return isSessionValid();
			}

			@Nullable
			@Override
			public String currentWalletId() {
				return openWalletId();
			}
		};
	}

	public void closeSession() {
		syncManager.stop();
		invalidateSendFlow();
		sessionExecutor.execute(() -> {
			teardownSendFlowOnExecutor();
			closeCurrentSession();
		});
	}

	private void invalidateSession() {
		syncManager.stop();
		wipePendingSeed();
		invalidateSendFlow();
		sessionExecutor.execute(() -> {
			teardownSendFlowOnExecutor();
			closeCurrentSession();
		});
	}

	private void closeCurrentSession() {
		closeCurrentSession(true);
	}

	/**
	 * Close the open native session. When {@code persist} is true (lock / close
	 * session) the encrypted scan cache is flushed first so scanning can resume;
	 * when false (delete / rename / rescan, where the cache is about to be
	 * shredded anyway) it is closed without a wasteful final write. Either way the
	 * unlocked session, its decrypted secrets and native handles are destroyed.
	 */
	private synchronized void closeCurrentSession(boolean persist) {
		syncManager.stop();
		MoneroEngine.Session s = openSession;
		File dir = openWorkDir;
		openSession = null;
		openWorkDir = null;
		openWalletId = null;
		sessionEpoch = -1;
		pendingSends = java.util.Collections.emptyList();
		if (s != null) {
			try {
				if (persist) {
					s.closePersisting();
				} else {
					s.close();
				}
			} catch (Throwable ignored) {
			}
		}
		if (dir != null) stripPlaintext(dir);
		syncStatus.postValue(XmrSyncStatus.of(XmrSyncState.LOCKED));
		history.postValue(java.util.Collections.emptyList());
		receiveList.postValue(java.util.Collections.emptyList());
	}

	/**
	 * Run a destructive teardown (close the open session if it is this wallet,
	 * then mutate/shred its cache) on the session executor and wait. openWallet
	 * also runs on the session executor and is the only writer of the
	 * deterministic cache directory, so serializing here guarantees a
	 * delete/rename/rescan on the crypto executor can never mutate or shred the
	 * cache dir while an open is restoring into it: the teardown runs entirely
	 * before or entirely after the open, never interleaved. Sync is stopped first
	 * so the session executor drains the never-ending sync loop and reaches this
	 * task.
	 */
	private void runSessionTeardown(java.util.concurrent.Callable<Void> action)
			throws Exception {
		syncManager.stop();
		java.util.concurrent.FutureTask<Void> task =
				new java.util.concurrent.FutureTask<>(action);
		sessionExecutor.execute(task);
		try {

			task.get(TEARDOWN_TIMEOUT_MS,
					java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (java.util.concurrent.TimeoutException te) {
			task.cancel(true);
			throw new XmrError.XmrException(XmrError.BUSY);
		} catch (java.util.concurrent.ExecutionException ee) {
			Throwable cause = ee.getCause();
			if (cause instanceof Exception) throw (Exception) cause;
			throw new RuntimeException(cause);
		}
	}

	private static final long TEARDOWN_TIMEOUT_MS = 45_000;

	private void fail(XmrError e, char[] walletPassword, @Nullable File dir) {
		java.util.Arrays.fill(walletPassword, '\0');
		if (dir != null) shred(dir);
		error.postValue(new Event<>(e));
		busy.postValue(false);
	}

	private void fail(XmrError e) {
		error.postValue(new Event<>(e));
		busy.postValue(false);
	}

	private static boolean isWrongPassword(Throwable e) {
		Throwable t = e;
		while (t != null) {
			if (t instanceof javax.crypto.AEADBadTagException
					|| t instanceof javax.crypto.BadPaddingException
					|| t instanceof SecurityException) {
				return true;
			}
			t = t.getCause();
		}
		return false;
	}

	private char[] randomFilePassword() {
		byte[] b = new byte[32];
		new SecureRandom().nextBytes(b);
		byte[] enc = java.util.Base64.getUrlEncoder().withoutPadding().encode(b);
		char[] out = new char[enc.length];
		for (int i = 0; i < enc.length; i++) {
			out[i] = (char) (enc[i] & 0xFF);
		}
		java.util.Arrays.fill(b, (byte) 0);
		java.util.Arrays.fill(enc, (byte) 0);
		return out;
	}

	private File newWorkDir() {
		byte[] r = new byte[9];
		new SecureRandom().nextBytes(r);
		String tag = java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString(r);
		File dir = new File(noBackupBase, "s-" + tag);
		dir.mkdirs();
		return dir;
	}

	private void shred(File dir) {
		try {
			File[] files = dir.listFiles();
			if (files != null) {
				for (File f : files) {
					if (f.isDirectory()) {
						shred(f);
					} else {
						shredFile(f);
					}
				}
			}
			dir.delete();
		} catch (Throwable ignored) {
		}
	}

	private void shredFile(File f) {
		try {
			long len = f.length();
			if (len > 0) {
				try (RandomAccessFile raf = new RandomAccessFile(f, "rws")) {
					byte[] buf = new byte[(int) Math.min(len, 1 << 16)];
					new SecureRandom().nextBytes(buf);
					long w = 0;
					while (w < len) {
						int n = (int) Math.min(buf.length, len - w);
						raf.write(buf, 0, n);
						w += n;
					}
					raf.getFD().sync();
				}
			}
		} catch (Throwable ignored) {
		}
		f.delete();
	}

	private File liveDir(String walletId) {
		return new File(new File(noBackupBase, "live"), dirNameFor(walletId));
	}

	private void sweepStaleWorkDirs() {
		if (!sweptResidue.compareAndSet(false, true)) return;
		try {
			File[] kids = noBackupBase.listFiles();
			if (kids != null) {
				for (File f : kids) {
					if (f.isDirectory() && f.getName().startsWith("s-")) {
						shred(f);
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	static String dirNameFor(String walletId) {
		String h = sha256Hex(walletId);
		return h.isEmpty() ? "_" : h.substring(0, Math.min(32, h.length()));
	}

	private static String sha256Hex(String s) {
		try {
			java.security.MessageDigest md =
					java.security.MessageDigest.getInstance("SHA-256");
			byte[] d = md.digest(
					s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
			StringBuilder sb = new StringBuilder(d.length * 2);
			for (byte b : d) {
				sb.append(Character.forDigit((b >> 4) & 0xF, 16));
				sb.append(Character.forDigit(b & 0xF, 16));
			}
			return sb.toString();
		} catch (Exception e) {
			return "";
		}
	}

	private boolean cacheFilesPresent(File dir) {
		return new File(dir, "w").isFile() && new File(dir, "w.keys").isFile();
	}

	private boolean backgroundFilesPresent(File dir) {
		return new File(dir, "w.background").isFile()
				&& new File(dir, "w.background.keys").isFile();
	}

	/**
	 * True when opening this wallet needs the wallet password: it is a legacy
	 * (pre second-layer) wallet, or its view-only background files are not yet
	 * built. A V2 wallet with its background files present opens for viewing
	 * with only the vault unlocked.
	 */
	public boolean needsPasswordToOpen(String walletId) {
		return isLegacyWallet(walletId)
				|| !backgroundFilesPresent(liveDir(walletId));
	}

	private void stripPlaintext(File dir) {
		try {
			File[] files = dir.listFiles();
			if (files != null) {
				for (File f : files) {
					if (f.isFile() && f.getName().endsWith(".address.txt")) {
						shredFile(f);
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	static final int WALLET_V2 = 2;

	private int walletCv(String walletId) {
		try {
			org.json.JSONObject xmr = settingsObject().optJSONObject("xmr");
			if (xmr != null) {
				org.json.JSONObject w = xmr.optJSONObject(walletId);
				if (w != null) return w.optInt("cv", 1);
			}
		} catch (Throwable ignored) {
		}
		return 1;
	}

	private boolean isLegacyWallet(String walletId) {
		return walletCv(walletId) < WALLET_V2;
	}

	private static final String BACKGROUND_PASSWORD_SECRET = "bgp";

	@Nullable
	private char[] loadBackgroundPassword(String walletId) {
		try {
			byte[] stored = walletStore.readWalletSecret(walletId,
					BACKGROUND_PASSWORD_SECRET);
			if (stored != null) {
				try {
					return utf8ToChars(stored);
				} finally {
					java.util.Arrays.fill(stored, (byte) 0);
				}
			}
			return moveBackgroundPasswordOutOfSettings(walletId);
		} catch (Throwable ignored) {
		}
		return null;
	}

	/**
	 * Wallets built before the background credential had its own vault item
	 * carried it inside the settings JSON. On first use it is moved to its
	 * item and removed from the JSON, so no later settings read or write
	 * copies it into a String again.
	 */
	@Nullable
	private char[] moveBackgroundPasswordOutOfSettings(String walletId)
			throws Exception {
		synchronized (walletStore.settingsMonitor()) {
			org.json.JSONObject o = settingsObject();
			org.json.JSONObject xmr = o.optJSONObject("xmr");
			if (xmr == null) return null;
			org.json.JSONObject w = xmr.optJSONObject(walletId);
			if (w == null) return null;
			String legacy = w.optString(BACKGROUND_PASSWORD_SECRET, "");
			if (legacy.isEmpty()) return null;
			char[] chars = legacy.toCharArray();
			byte[] bytes = charsToUtf8(chars);
			try {
				walletStore.writeWalletSecret(walletId,
						BACKGROUND_PASSWORD_SECRET, bytes);
			} finally {
				java.util.Arrays.fill(bytes, (byte) 0);
			}
			w.remove(BACKGROUND_PASSWORD_SECRET);
			xmr.put(walletId, w);
			o.put("xmr", xmr);
			walletStore.writeSettings(o.toString());
			return chars;
		}
	}

	private static byte[] charsToUtf8(char[] chars) {
		java.nio.ByteBuffer bb = java.nio.charset.StandardCharsets.UTF_8
				.encode(java.nio.CharBuffer.wrap(chars));
		byte[] out = new byte[bb.remaining()];
		bb.get(out);
		java.util.Arrays.fill(bb.array(), (byte) 0);
		return out;
	}

	private static char[] utf8ToChars(byte[] bytes) {
		java.nio.CharBuffer cb = java.nio.charset.StandardCharsets.UTF_8
				.decode(java.nio.ByteBuffer.wrap(bytes));
		char[] out = new char[cb.remaining()];
		cb.get(out);
		java.util.Arrays.fill(cb.array(), '\0');
		return out;
	}

	@Nullable
	private byte[] loadKekSalt(String walletId) {
		try {
			org.json.JSONObject xmr = settingsObject().optJSONObject("xmr");
			if (xmr != null) {
				org.json.JSONObject w = xmr.optJSONObject(walletId);
				if (w != null) {
					String ks = w.optString("ks", "");
					if (!ks.isEmpty()) {
						return java.util.Base64.getDecoder().decode(ks);
					}
				}
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	/**
	 * Commit the second-layer wallet metadata as one atomic settings write: the
	 * vault-tier background credential, the wallet key-derivation salt and
	 * version, the address fingerprint, version 2, and the removal of any legacy
	 * vault-tier full-wallet file password. After this returns the wallet is a
	 * V2 wallet whose spend key is reachable only through the wallet password.
	 */
	private void persistWalletV2(String walletId, char[] backgroundPw,
			byte[] kekSalt, @Nullable String primaryAddress) throws Exception {
		synchronized (walletStore.settingsMonitor()) {
			org.json.JSONObject o = settingsObject();
			org.json.JSONObject xmr = o.optJSONObject("xmr");
			if (xmr == null) xmr = new org.json.JSONObject();
			org.json.JSONObject w = xmr.optJSONObject(walletId);
			if (w == null) w = new org.json.JSONObject();
			byte[] bgpBytes = charsToUtf8(backgroundPw);
			try {
				walletStore.writeWalletSecret(walletId,
						BACKGROUND_PASSWORD_SECRET, bgpBytes);
			} finally {
				java.util.Arrays.fill(bgpBytes, (byte) 0);
			}
			w.remove(BACKGROUND_PASSWORD_SECRET);
			w.put("ks", java.util.Base64.getEncoder()
					.encodeToString(kekSalt));
			w.put("kv", XmrWalletKek.VERSION);
			w.put("cv", WALLET_V2);
			w.remove("fp");
			if (primaryAddress != null && !primaryAddress.isEmpty()) {
				w.put("af", sha256Hex(primaryAddress));
			}
			xmr.put(walletId, w);
			o.put("xmr", xmr);
			walletStore.writeSettings(o.toString());
		}
	}

	/**
	 * Build the two-layer on-disk wallet in {@code dir} from the seed: the
	 * spend-capable main wallet under a wallet-password-derived key, then a
	 * Monero-native view-only background wallet under a fresh random vault-tier
	 * credential. Verifies before persisting that the background wallet is a
	 * background wallet, carries the same primary address, and exposes no seed,
	 * so a build that did not achieve the spend/view split fails closed and
	 * never commits. Returns the verified primary address. Leaves the files in
	 * {@code dir} and closes every native session it opened.
	 */
	private String establishV2(String walletId, File dir, char[] seed,
			long height, char[] walletPassword) throws Exception {
		byte[] salt = XmrWalletKek.newSalt();
		char[] mainPw = XmrWalletKek.deriveMainFilePassword(walletPassword, salt);
		char[] backgroundPw = randomFilePassword();
		MoneroEngine.Session spend = null;
		MoneroEngine.Session view = null;
		try {
			String base = new File(dir, "w").getAbsolutePath();
			spend = engine.restore(base, mainPw, seed, height, new char[0]);
			if (spend == null || spend.status() != 0) {
				throw new XmrError.XmrException(XmrError.NATIVE_CREATE_FAILED);
			}
			String address = spend.address(0, 0);
			if (address == null || address.isEmpty()) {
				throw new XmrError.XmrException(XmrError.NATIVE_CREATE_FAILED);
			}
			if (!spend.setupBackgroundSync(mainPw, backgroundPw)) {
				throw new XmrError.XmrException(XmrError.NATIVE_CREATE_FAILED);
			}
			if (!spend.store(base)) {
				throw new XmrError.XmrException(XmrError.STORAGE_COMMIT_FAILED);
			}
			spend.close();
			spend = null;

			view = engine.open(
					new File(dir, "w.background").getAbsolutePath(),
					backgroundPw);
			if (view == null || view.status() != 0
					|| !view.isBackgroundWallet()) {
				throw new XmrError.XmrException(XmrError.NATIVE_CREATE_FAILED);
			}
			String viewAddr = view.address(0, 0);
			if (viewAddr == null || !viewAddr.equals(address)) {
				throw new XmrError.XmrException(XmrError.NATIVE_CREATE_FAILED);
			}
			char[] viewSeed = view.seed(new char[0]);
			boolean noSpendKey = viewSeed == null || viewSeed.length == 0;
			if (viewSeed != null) java.util.Arrays.fill(viewSeed, '\0');
			if (!noSpendKey) {
				throw new XmrError.XmrException(XmrError.NATIVE_CREATE_FAILED);
			}
			view.close();
			view = null;

			stripPlaintext(dir);
			persistWalletV2(walletId, backgroundPw, salt, address);
			return address;
		} finally {
			if (spend != null) spend.close();
			if (view != null) view.close();
			java.util.Arrays.fill(mainPw, '\0');
			java.util.Arrays.fill(backgroundPw, '\0');
			java.util.Arrays.fill(salt, (byte) 0);
		}
	}

	/**
	 * Open the spend-capable main wallet for a V2 wallet by deriving its
	 * main-file password from the wallet password. Returns null if the wallet
	 * password is wrong (the native open reports an error status) or the wallet
	 * is not spendable. The caller owns the returned session and must close it
	 * as soon as the spend is done.
	 */
	/**
	 * Open the spend-capable main wallet for a send and bring it ready to
	 * construct a transaction. Opening the main file merges the outputs the
	 * view-only background wallet has already scanned (wallet2
	 * process_background_cache_on_open), so no full re-scan is needed; the
	 * session is then connected to the same node the sync loop was using and
	 * refreshed to the tip, so the transaction can fetch decoys and relay.
	 * Returns null on a wrong password, a non-spendable open, or if it cannot
	 * connect (a send must never be built against a disconnected wallet).
	 */
	private MoneroEngine.Session openSpendSession(String walletId,
			char[] walletPassword, XmrNode node) throws XmrError.XmrException {
		byte[] salt = loadKekSalt(walletId);
		if (salt == null) {
			throw new XmrError.XmrException(XmrError.CORRUPTED_ITEM);
		}
		char[] mainPw = XmrWalletKek.deriveMainFilePassword(walletPassword, salt);
		try {
			File dir = liveDir(walletId);
			MoneroEngine.Session s = engine.open(
					new File(dir, "w").getAbsolutePath(), mainPw);
			if (s == null || s.status() != 0 || s.isBackgroundWallet()) {
				if (s != null) s.close();
				throw new XmrError.XmrException(XmrError.WRONG_PASSWORD);
			}
			String proxy = node.usesTor()
					? XmrTorIsolation.relayProxy(torSocksPort, walletId) : "";
			boolean connected;
			try {
				connected = s.init(node.address(), proxy, node.trusted)
						&& s.connectionStatus() == 1;
			} catch (Throwable e) {
				connected = false;
			}
			if (!connected) {
				s.close();
				throw new XmrError.XmrException(XmrError.NODE_UNREACHABLE);
			}
			try {
				s.refresh();
			} catch (Throwable ignored) {
			}
			return s;
		} finally {
			java.util.Arrays.fill(mainPw, '\0');
			java.util.Arrays.fill(salt, (byte) 0);
		}
	}

	/**
	 * Verify an opened session's primary address matches the stored fingerprint,
	 * independent of cache version. Rejects a foreign or corrupt background file
	 * that happens to open as a valid background wallet but belongs to a
	 * different wallet.
	 */
	private boolean identityMatchesStored(MoneroEngine.Session s,
			String walletId) {
		try {
			org.json.JSONObject xmr = settingsObject().optJSONObject("xmr");
			if (xmr == null) return false;
			org.json.JSONObject w = xmr.optJSONObject(walletId);
			if (w == null) return false;
			String af = w.optString("af", "");
			if (af.isEmpty()) return false;
			String addr = s.address(0, 0);
			return addr != null && af.equals(sha256Hex(addr));
		} catch (Throwable e) {
			return false;
		}
	}

	/** Shred the cache directory and drop its file password + identity manifest. */
	private void clearCache(String walletId) {
		try {
			shred(liveDir(walletId));
		} catch (Throwable ignored) {
		}
		try {
			synchronized (walletStore.settingsMonitor()) {
				org.json.JSONObject o = settingsObject();
				org.json.JSONObject xmr = o.optJSONObject("xmr");
				if (xmr != null) {
					org.json.JSONObject w = xmr.optJSONObject(walletId);
					if (w != null) {
						w.remove("fp");
						w.remove("af");
						w.remove("cv");
						xmr.put(walletId, w);
						o.put("xmr", xmr);
						walletStore.writeSettings(o.toString());
					}
				}
			}
		} catch (Throwable ignored) {
		}
	}

	/**
	 * A wallet created while the device clock ran ahead persists a restore
	 * height above the real chain tip, and the scan then waits for that
	 * height and never sees payments made in between. The first daemon
	 * height seen for such a wallet caps the persisted height a safety margin
	 * below the tip and rescans the freshly created wallet from there; the
	 * marker is cleared so this runs once per creation.
	 */
	private void correctClockHeightOnce(@Nullable String walletId,
			long daemonHeight) {
		if (walletId == null || daemonHeight <= 0) return;
		if (!heightFromClock(walletId)) return;
		long cap = Math.max(0, daemonHeight - XmrBirthday.BASE_MARGIN_BLOCKS);
		long persisted = readRestoreHeight(walletId);
		try {
			persistHeightFromClock(walletId, false);
			if (persisted <= cap) return;
			persistRestoreHeight(walletId, cap);
		} catch (Throwable ignored) {
			return;
		}
		syncManager.submit(() -> {
			MoneroEngine.Session s = openSession;
			if (s == null || !walletId.equals(openWalletId)
					|| !isSessionValid()) {
				return;
			}
			try {
				s.pauseRefresh();
				s.stopRefresh();
				s.waitRefreshIdle(SEND_REFRESH_IDLE_TIMEOUT_MS);
				s.setRefreshFromHeight(cap);
				s.rescanBlockchain();
			} catch (Throwable ignored) {
			} finally {
				try {
					s.startRefresh();
				} catch (Throwable ignored) {
				}
			}
		});
	}

	private boolean heightFromClock(String walletId) {
		try {
			org.json.JSONObject xmr = settingsObject().optJSONObject("xmr");
			if (xmr != null) {
				org.json.JSONObject w = xmr.optJSONObject(walletId);
				if (w != null) return w.optBoolean("hEst", false);
			}
		} catch (Throwable ignored) {
		}
		return false;
	}

	private void persistHeightFromClock(String walletId, boolean fromClock)
			throws Exception {
		synchronized (walletStore.settingsMonitor()) {
			org.json.JSONObject o = settingsObject();
			org.json.JSONObject xmr = o.optJSONObject("xmr");
			if (xmr == null) xmr = new org.json.JSONObject();
			org.json.JSONObject w = xmr.optJSONObject(walletId);
			if (w == null) w = new org.json.JSONObject();
			if (fromClock) w.put("hEst", true);
			else w.remove("hEst");
			xmr.put(walletId, w);
			o.put("xmr", xmr);
			walletStore.writeSettings(o.toString());
		}
	}

	private void persistRestoreHeight(String walletId, long height)
			throws Exception {
		synchronized (walletStore.settingsMonitor()) {
			org.json.JSONObject o = settingsObject();
			org.json.JSONObject xmr = o.optJSONObject("xmr");
			if (xmr == null) xmr = new org.json.JSONObject();
			org.json.JSONObject w = xmr.optJSONObject(walletId);
			if (w == null) w = new org.json.JSONObject();
			w.put("h", height);
			xmr.put(walletId, w);
			o.put("xmr", xmr);
			walletStore.writeSettings(o.toString());
		}
	}

	private long readRestoreHeight(String walletId) {
		try {
			org.json.JSONObject xmr = settingsObject().optJSONObject("xmr");
			if (xmr != null) {
				org.json.JSONObject w = xmr.optJSONObject(walletId);
				if (w != null) return Math.max(w.optLong("h", 0), 0);
			}
		} catch (Throwable ignored) {
		}
		return 0;
	}

	/**
	 * The wallet's mutable display name, stored as presentation metadata keyed to
	 * the immutable wallet id. A rename updates only this, never the seed, keys,
	 * native files, journal or any other id-keyed state. Falls back to the given
	 * value (the coin-tagged label parsed from the vault item) for wallets created
	 * before the name was tracked here.
	 */
	private String readDisplayName(String walletId, String fallback) {
		try {
			org.json.JSONObject xmr = settingsObject().optJSONObject("xmr");
			if (xmr != null) {
				org.json.JSONObject w = xmr.optJSONObject(walletId);
				if (w != null) {
					String nm = w.optString("nm", "");
					if (!nm.isEmpty()) return nm;
				}
			}
		} catch (Throwable ignored) {
		}
		return fallback;
	}

	private void persistDisplayName(String walletId, String name)
			throws Exception {
		synchronized (walletStore.settingsMonitor()) {
			org.json.JSONObject o = settingsObject();
			org.json.JSONObject xmr = o.optJSONObject("xmr");
			if (xmr == null) xmr = new org.json.JSONObject();
			org.json.JSONObject w = xmr.optJSONObject(walletId);
			if (w == null) w = new org.json.JSONObject();
			w.put("nm", name);
			xmr.put(walletId, w);
			o.put("xmr", xmr);
			walletStore.writeSettings(o.toString());
		}
	}

	private org.json.JSONObject settingsObject() throws Exception {
		String json = walletStore.readSettings();
		return json == null ? new org.json.JSONObject()
				: new org.json.JSONObject(json);
	}
}
