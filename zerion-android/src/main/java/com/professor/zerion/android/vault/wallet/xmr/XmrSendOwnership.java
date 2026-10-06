package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

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
