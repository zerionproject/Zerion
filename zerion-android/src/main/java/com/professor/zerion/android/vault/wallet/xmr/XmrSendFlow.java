package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Set;
import java.util.function.Supplier;

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

	public long changeAtomic() {
		MoneroEngine.Prepared p = prepared;
		if (p == null || p.isDisposed()) return 0;
		long c = p.changeAtomic();
		return c > 0 ? c : 0;
	}

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

		boolean relayed;
		try {
			relayed = p.commit();
		} catch (Throwable connectionLost) {
			relayed = false;
		}
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

	public void cancel() {
		if (state == State.SUCCESS || state == State.RELAY_UNCERTAIN) return;
		state = State.CANCELLED;
		invalidateToken();
		disposePrepared();
	}

	public void invalidate() {
		if (state == State.SUCCESS || state == State.RELAY_UNCERTAIN
				|| state == State.RELAYING) {
			invalidateToken();
			return;
		}
		state = State.CANCELLED;
		invalidateToken();
	}

	public void disposeOnExecutor() {
		disposePrepared();
	}

	public String walletId() {
		return walletId;
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
