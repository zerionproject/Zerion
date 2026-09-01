package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Set;
import java.util.function.Supplier;

/**
 * The core Monero send state machine, with no UI dependency as a security
 * authority. It drives one send from input through a reviewed, signed
 * transaction, a fresh authentication, and, on the same serialized
 * session-executor operation, the ownership-checked relay: pre-ownership
 * validation, native re-read, post-ownership validation, fingerprint comparison,
 * single-use authorization consumption, capture of the connected relay endpoint,
 * a durable journal write, and only then nCommit on the same prepared
 * transaction. There is no thread or UI gap between final validation and the
 * journal and relay, no silent reconstruction of a different transaction, and no
 * silent change of node or clearnet fallback.
 *
 * <p>Every method that touches the native transaction runs on the single
 * session executor. {@link #invalidate()} may be called from a lock listener on
 * another thread and only flips state and invalidates the authorization, never
 * touching the native object; the native transaction it leaves behind is freed
 * on the executor through {@link #disposeOnExecutor()}. {@link #cancel()} frees
 * the native object inline and is only invoked on the session executor.
 */
@NotNullByDefault
public final class XmrSendFlow {

	public enum State {
		INPUT, VALIDATING, PREPARING, REVIEW_READY, AUTHENTICATING, AUTHORIZED,
		RELAYING, SUCCESS, FAILED, RELAY_UNCERTAIN, CANCELLED
	}

	public enum RelayResult { SUCCESS, RELAY_UNCERTAIN, FAILED }

	private final MoneroEngine engine;
	private final MoneroEngine.Session session;
	private final long account;
	private final String walletId;
	private final XmrSendGate gate;
	private final XmrSpendJournalStore journalStore;
	private final XmrSendGate.SendGuard guard;
	private final Supplier<String> endpointIdSupplier;
	private final Supplier<Set<String>> outgoingHistorySupplier;
	private final long refreshIdleTimeoutMs;

	private final Object flowToken = new Object();
	private volatile State state = State.INPUT;

	@Nullable
	private volatile MoneroEngine.Prepared prepared;
	@Nullable
	private XmrSendSnapshot snapshot;
	@Nullable
	private XmrAuthToken token;

	public XmrSendFlow(MoneroEngine engine, MoneroEngine.Session session,
			long account, String walletId, XmrSendGate gate,
			XmrSpendJournalStore journalStore, XmrSendGate.SendGuard guard,
			Supplier<String> endpointIdSupplier,
			Supplier<Set<String>> outgoingHistorySupplier,
			long refreshIdleTimeoutMs) {
		this.engine = engine;
		this.session = session;
		this.account = account;
		this.walletId = walletId;
		this.gate = gate;
		this.journalStore = journalStore;
		this.guard = guard;
		this.endpointIdSupplier = endpointIdSupplier;
		this.outgoingHistorySupplier = outgoingHistorySupplier;
		this.refreshIdleTimeoutMs = refreshIdleTimeoutMs;
	}

	public State state() {
		return state;
	}

	@Nullable
	public XmrSendSnapshot snapshot() {
		return snapshot;
	}

	/**
	 * Total change of the prepared transaction, read from the exact signed tx for
	 * the display balance reservation (never guessed). Zero when unavailable or
	 * on a sweep. Must be read while the prepared transaction is still alive
	 * (before relay teardown).
	 */
	public long changeAtomic() {
		MoneroEngine.Prepared p = prepared;
		if (p == null || p.isDisposed()) return 0;
		long c = p.changeAtomic();
		return c > 0 ? c : 0;
	}

	/**
	 * Validate the destination and amount, quiesce refresh, build and sign the
	 * transaction, and take the immutable review snapshot. Rejects a quarantined
	 * wallet before any construction. On success the flow is REVIEW_READY.
	 */
	public void prepare(String destination, long amountAtomic, int priority,
			byte[] primaryFingerprint) throws XmrError.XmrException {
		if (state != State.INPUT) throw fail(XmrError.UNKNOWN);
		state = State.VALIDATING;

		if (journalStore.isQuarantined(walletId)) {
			state = State.FAILED;
			throw new XmrError.XmrException(XmrError.SPEND_QUARANTINED);
		}
		if (amountAtomic <= 0) throw fail(XmrError.SEND_SNAPSHOT_INVALID);
		MoneroEngine.AddressKind kind = engine.addressKind(destination);
		if (kind == MoneroEngine.AddressKind.INVALID) {
			throw fail(XmrError.SEND_SNAPSHOT_INVALID);
		}
		if (!guard.sessionValid()
				|| !walletId.equals(guard.currentWalletId())) {
			throw fail(XmrError.SESSION_INVALIDATED);
		}

		state = State.PREPARING;
		session.pauseRefresh();
		session.stopRefresh();
		if (!session.waitRefreshIdle(refreshIdleTimeoutMs)) {
			resumeRefresh();
			throw fail(XmrError.BUSY);
		}
		if (state == State.CANCELLED || !guard.sessionValid()
				|| !walletId.equals(guard.currentWalletId())) {
			resumeRefresh();
			throw fail(XmrError.SESSION_INVALIDATED);
		}

		MoneroEngine.Prepared p =
				session.prepare(destination, amountAtomic, priority, account);
		if (p == null) {
			resumeRefresh();
			throw fail(XmrError.UNKNOWN);
		}
		try {
			XmrSendSnapshot s = XmrSendSnapshot.fromPrepared(walletId,
					primaryFingerprint, XmrSendSnapshot.NETWORK_MAINNET,
					destination, kind, p);
			this.prepared = p;
			this.snapshot = s;
			state = State.REVIEW_READY;
		} catch (XmrError.XmrException invalid) {
			p.close();
			resumeRefresh();
			throw fail(invalid.error);
		}
	}

	/**
	 * Fresh per-transaction authentication after the snapshot exists. Produces a
	 * single-use token bound to the snapshot, ownership and generation.
	 */
	public void authorize(char[] password) throws XmrError.XmrException {
		if (state != State.REVIEW_READY || snapshot == null || prepared == null) {
			throw fail(XmrError.UNKNOWN);
		}
		state = State.AUTHENTICATING;
		XmrSendOwnership ownership = new XmrSendOwnership(prepared, session,
				walletId, guard.sessionEpoch(), guard.lockGeneration(),
				flowToken);
		try {
			this.token = gate.authorize(snapshot, ownership, password);
		} catch (XmrError.XmrException e) {
			state = State.REVIEW_READY;
			throw e;
		}
		state = State.AUTHORIZED;
	}

	/**
	 * The single serialized relay operation. Validates against the live native
	 * object and generation, consumes the authorization once, captures the
	 * connected relay endpoint, writes the journal durably, and only then
	 * relays. A journal write failure means no relay. It never reconstructs a
	 * different transaction or changes node.
	 */
	public RelayResult confirmAndRelay() {
		if (state != State.AUTHORIZED || snapshot == null || prepared == null
				|| token == null) {
			return RelayResult.FAILED;
		}
		state = State.RELAYING;
		MoneroEngine.Prepared p = prepared;
		XmrSendSnapshot s = snapshot;
		XmrAuthToken t = token;

		try {
			gate.validateForRelay(t, s, p, session, flowToken);
		} catch (XmrError.XmrException e) {
			state = State.FAILED;
			disposePrepared();
			return RelayResult.FAILED;
		}

		String endpointId = endpointIdSupplier.get();
		if (endpointId == null || endpointId.isEmpty()) {
			state = State.FAILED;
			disposePrepared();
			return RelayResult.FAILED;
		}

		XmrSpendJournal journal;
		try {
			journal = XmrSpendJournal.create(XmrSpendJournal.State.RELAYING,
					walletId, bytesToHex(s.primaryWalletFingerprint()),
					s.txids(), endpointId, System.currentTimeMillis(),
					java.util.Collections.emptyList());
			journalStore.writeDurably(journal);
		} catch (XmrError.XmrException journalFailed) {
			state = State.FAILED;
			disposePrepared();
			return RelayResult.FAILED;
		}

		boolean relayed = p.commit();
		disposePrepared();

		if (relayed) {
			try {
				Set<String> accepted = XmrSpendReconciler.acceptedFrom(
						java.util.Collections.emptyList(),
						outgoingHistorySupplier.get());
				if (XmrSpendReconciler.decide(journal, accepted)
						== XmrSpendReconciler.Outcome.RESOLVED) {
					journalStore.clear(walletId);
				}
			} catch (Exception ignored) {
			}
			state = State.SUCCESS;
			return RelayResult.SUCCESS;
		}

		state = State.RELAY_UNCERTAIN;
		return RelayResult.RELAY_UNCERTAIN;
	}

	/** User cancel before relay: dispose the transaction and kill the auth. */
	public void cancel() {
		if (state == State.SUCCESS || state == State.RELAY_UNCERTAIN) return;
		state = State.CANCELLED;
		invalidateToken();
		disposePrepared();
	}

	/**
	 * Vault lock or session replacement: invalidate the authorization at once.
	 * Safe to call from a lock listener off the session executor: it only flips
	 * state and kills the token, never touching the native object, so it cannot
	 * race the executor's relay. The native transaction is freed separately on
	 * the executor through {@link #disposeOnExecutor()}. If a relay is already
	 * past its durable journal write this cannot unsend it, but it guarantees no
	 * new relay begins.
	 */
	public void invalidate() {
		if (state == State.SUCCESS || state == State.RELAY_UNCERTAIN
				|| state == State.RELAYING) {
			invalidateToken();
			return;
		}
		state = State.CANCELLED;
		invalidateToken();
	}

	/**
	 * Free the prepared native transaction. Must run on the session executor so
	 * it can never race a concurrent native read or relay. Idempotent: a
	 * transaction already disposed by the relay path is a no-op.
	 */
	public void disposeOnExecutor() {
		disposePrepared();
	}

	private void resumeRefresh() {
		try {
			session.startRefresh();
		} catch (RuntimeException ignored) {
		}
	}

	private void invalidateToken() {
		XmrAuthToken t = token;
		if (t != null) t.invalidate();
		gate.invalidateActive();
	}

	private void disposePrepared() {
		MoneroEngine.Prepared p = prepared;
		if (p != null && !p.isDisposed()) p.close();
		prepared = null;
	}

	private XmrError.XmrException fail(XmrError e) {
		state = State.FAILED;
		return new XmrError.XmrException(e);
	}

	private static String bytesToHex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) sb.append(String.format("%02x", x & 0xff));
		return sb.toString();
	}
}
