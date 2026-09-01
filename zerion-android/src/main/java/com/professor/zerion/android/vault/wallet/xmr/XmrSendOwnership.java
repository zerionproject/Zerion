package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Process-local binding of a prepared transaction to the exact live objects and
 * session generation that produced it. It is never persisted and is never part
 * of the {@link XmrSendFingerprint}: it uses object identity, which is a
 * process-local fact, not a cryptographic one. Every native operation on the
 * prepared transaction, and the final check before relay, must verify that the
 * current objects and generations still match the ones captured here.
 *
 * <p>A replaced native transaction, a reopened or replaced session, a changed
 * session epoch or vault lock generation, a wallet switch, the wrong active
 * flow, or a disposed object each make {@link #matches} return false, so a stale
 * authorization can never be applied to a different transaction or session.
 */
@NotNullByDefault
public final class XmrSendOwnership {

	private final Object preparedHandle;
	private final Object session;
	private final String walletId;
	private final long sessionEpoch;
	private final long lockGeneration;
	private final Object flowToken;

	public XmrSendOwnership(Object preparedHandle, Object session,
			String walletId, long sessionEpoch, long lockGeneration,
			Object flowToken) {
		this.preparedHandle = preparedHandle;
		this.session = session;
		this.walletId = walletId;
		this.sessionEpoch = sessionEpoch;
		this.lockGeneration = lockGeneration;
		this.flowToken = flowToken;
	}

	public long sessionEpoch() {
		return sessionEpoch;
	}

	public long lockGeneration() {
		return lockGeneration;
	}

	public String walletId() {
		return walletId;
	}

	/**
	 * True only when every process-local binding still holds: the same prepared
	 * object and session object by identity, the same active flow token by
	 * identity, the same wallet id, and unchanged session epoch and lock
	 * generation. Any null or any difference fails closed.
	 */
	public boolean matches(Object currentPrepared, Object currentSession,
			String currentWalletId, long currentEpoch, long currentLockGen,
			Object currentFlow) {
		return preparedHandle == currentPrepared
				&& session == currentSession
				&& flowToken == currentFlow
				&& walletId.equals(currentWalletId)
				&& sessionEpoch == currentEpoch
				&& lockGeneration == currentLockGen;
	}
}
