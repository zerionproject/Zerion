package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

@NotNullByDefault
public final class XmrAuthToken {

	private final byte[] fingerprint;
	private final XmrSendOwnership ownership;
	private final long sessionEpoch;
	private final long lockGeneration;
	private final long issuedMonotonicMs;
	private final long ttlMs;

	private final AtomicBoolean consumed = new AtomicBoolean(false);
	private final AtomicBoolean invalidated = new AtomicBoolean(false);

	XmrAuthToken(byte[] fingerprint, XmrSendOwnership ownership,
			long sessionEpoch, long lockGeneration, long issuedMonotonicMs,
			long ttlMs) {
		this.fingerprint = fingerprint.clone();
		this.ownership = ownership;
		this.sessionEpoch = sessionEpoch;
		this.lockGeneration = lockGeneration;
		this.issuedMonotonicMs = issuedMonotonicMs;
		this.ttlMs = ttlMs;
	}

	public void invalidate() {
		invalidated.set(true);
	}

	public boolean isConsumed() {
		return consumed.get();
	}

	public boolean isInvalidated() {
		return invalidated.get();
	}

	public boolean isLive(long nowMonotonicMs) {
		if (consumed.get() || invalidated.get()) return false;
		long elapsed = nowMonotonicMs - issuedMonotonicMs;
		return elapsed >= 0 && elapsed <= ttlMs;
	}

	public boolean bindsTo(Object currentPrepared, Object currentSession,
			String currentWalletId, long currentEpoch, long currentLockGen,
			Object currentFlow) {
		return currentEpoch == sessionEpoch
				&& currentLockGen == lockGeneration
				&& ownership.matches(currentPrepared, currentSession,
						currentWalletId, currentEpoch, currentLockGen,
						currentFlow);
	}

	public boolean consume(long nowMonotonicMs, byte[] currentFingerprint,
			Object currentPrepared, Object currentSession,
			String currentWalletId, long currentEpoch, long currentLockGen,
			Object currentFlow) {
		if (invalidated.get() || consumed.get()) return false;
		long elapsed = nowMonotonicMs - issuedMonotonicMs;
		if (elapsed < 0 || elapsed > ttlMs) {
			invalidate();
			return false;
		}
		if (!MessageDigest.isEqual(fingerprint, currentFingerprint)
				|| currentEpoch != sessionEpoch
				|| currentLockGen != lockGeneration
				|| !ownership.matches(currentPrepared, currentSession,
						currentWalletId, currentEpoch, currentLockGen,
						currentFlow)) {
			invalidate();
			return false;
		}
		return consumed.compareAndSet(false, true);
	}
}
