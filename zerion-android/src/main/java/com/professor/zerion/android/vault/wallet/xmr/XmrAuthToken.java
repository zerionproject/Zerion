package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A memory-only, single-use authorization for exactly one reviewed and signed
 * transaction. It carries a copy of the authorized fingerprint and the process
 * ownership it was issued against, plus the session epoch and lock generation at
 * issue time and a monotonic issue timestamp. It holds no password, no mnemonic
 * and no reusable credential, and it is never persisted, so process death
 * destroys it.
 *
 * <p>Authorization is exactly once. {@link #consume} verifies expiry, the
 * fingerprint (constant time), the session epoch and lock generation, and the
 * full ownership binding, and only then atomically flips a compare-and-set
 * consumed flag, so two concurrent auth results or two relay attempts can never
 * both succeed. Any mismatch, or any invalidating event, marks the token
 * permanently invalid.
 */
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

	/** Permanently invalidate the token (cancel, lock, mutation, replacement). */
	public void invalidate() {
		invalidated.set(true);
	}

	public boolean isConsumed() {
		return consumed.get();
	}

	public boolean isInvalidated() {
		return invalidated.get();
	}

	/**
	 * True while the token could still be consumed: not consumed, not
	 * invalidated, and within its lifetime measured on the monotonic clock. A
	 * time reading before issue (never expected from a monotonic clock) is
	 * treated as invalid.
	 */
	public boolean isLive(long nowMonotonicMs) {
		if (consumed.get() || invalidated.get()) return false;
		long elapsed = nowMonotonicMs - issuedMonotonicMs;
		return elapsed >= 0 && elapsed <= ttlMs;
	}

	/**
	 * Side-effect-free binding check used before and after the native inspection:
	 * the live session epoch and lock generation still match, and the full
	 * ownership (prepared object, session object, wallet id, epoch, lock
	 * generation and flow token) still holds by identity. It reads no native
	 * transaction value, consumes nothing, and invalidates nothing.
	 */
	public boolean bindsTo(Object currentPrepared, Object currentSession,
			String currentWalletId, long currentEpoch, long currentLockGen,
			Object currentFlow) {
		return currentEpoch == sessionEpoch
				&& currentLockGen == lockGeneration
				&& ownership.matches(currentPrepared, currentSession,
						currentWalletId, currentEpoch, currentLockGen,
						currentFlow);
	}

	/**
	 * Attempt the one-and-only consumption. Verifies lifetime, the fingerprint,
	 * the epoch and lock generation, and the full ownership binding against the
	 * live values; on any failure the token is invalidated and false is
	 * returned. On full success it atomically claims the single use and returns
	 * true; a second concurrent or later caller that loses the compare-and-set
	 * gets false. The token never carries or exposes a credential.
	 */
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
