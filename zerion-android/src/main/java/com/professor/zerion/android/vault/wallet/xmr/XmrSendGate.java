package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fresh-authentication authority for a reviewed Monero send. It runs after the
 * immutable signed {@link XmrSendSnapshot} exists, verifies the user's per-wallet
 * password by an authenticated decrypt (never trusting an already-unlocked vault
 * on its own), wipes the credential and the decrypted mnemonic immediately, and
 * issues a memory-only, single-use {@link XmrAuthToken} bound to the snapshot
 * fingerprint, the process ownership, the session epoch and the lock generation.
 * It relays nothing; a later commit consumes the token through
 * {@link #validateForRelay}, which re-reads the live native object, recomputes
 * the fingerprint and fails closed on any mutation.
 *
 * <p>The authorization is bound to the exact reviewed transaction and the exact
 * live session, and is exactly once: only one token is active at a time, and a
 * new authorization or any invalidating event kills the previous one.
 */
@NotNullByDefault
public final class XmrSendGate {

	private static final long DEFAULT_TTL_MS = 30_000;

	/** Live session generation and identity, backed by the wallet manager. */
	public interface SendGuard {
		long sessionEpoch();

		long lockGeneration();

		boolean sessionValid();

		@Nullable
		String currentWalletId();
	}

	/** Monotonic time source; never wall-clock. */
	public interface MonotonicClock {
		long nowMonotonicMs();
	}

	private final XmrStore store;
	private final SendGuard guard;
	private final MonotonicClock clock;
	private final long ttlMs;

	private final AtomicReference<XmrAuthToken> active = new AtomicReference<>();

	public XmrSendGate(XmrStore store, SendGuard guard, MonotonicClock clock) {
		this(store, guard, clock, DEFAULT_TTL_MS);
	}

	XmrSendGate(XmrStore store, SendGuard guard, MonotonicClock clock,
			long ttlMs) {
		this.store = store;
		this.guard = guard;
		this.clock = clock;
		this.ttlMs = Math.min(ttlMs, DEFAULT_TTL_MS);
	}

	/**
	 * Verify the per-wallet password against the current snapshot and ownership,
	 * then issue a single-use token. The credential enters as a char[] and the
	 * decrypted mnemonic is returned as a char[]; both are wiped immediately and
	 * neither becomes a String, enters the snapshot or token, or reaches a native
	 * send call. An unlocked vault alone never authorizes: the decrypt runs
	 * fresh here. Fails closed if the session changed since the snapshot was
	 * taken.
	 */
	public XmrAuthToken authorize(XmrSendSnapshot snapshot,
			XmrSendOwnership ownership, char[] password)
			throws XmrError.XmrException {
		long epoch = guard.sessionEpoch();
		long lockGen = guard.lockGeneration();
		String currentWallet = guard.currentWalletId();
		if (!guard.sessionValid() || currentWallet == null
				|| !snapshot.walletId().equals(currentWallet)
				|| epoch != ownership.sessionEpoch()
				|| lockGen != ownership.lockGeneration()) {
			throw new XmrError.XmrException(XmrError.SESSION_INVALIDATED);
		}

		char[] mnemonic = null;
		try {
			mnemonic = store.loadMnemonicChars(snapshot.walletId(), password);
		} catch (Exception e) {
			throw new XmrError.XmrException(wrongOrEmpty(password), e);
		} finally {
			if (mnemonic != null) Arrays.fill(mnemonic, '\0');
			if (password != null) Arrays.fill(password, '\0');
		}

		XmrAuthToken token = new XmrAuthToken(snapshot.fingerprint(), ownership,
				epoch, lockGen, clock.nowMonotonicMs(), ttlMs);
		XmrAuthToken previous = active.getAndSet(token);
		if (previous != null) previous.invalidate();
		return token;
	}

	/** Invalidate the active authorization (cancel, lock, wallet switch). */
	public void invalidateActive() {
		XmrAuthToken previous = active.getAndSet(null);
		if (previous != null) previous.invalidate();
	}

	@Nullable
	XmrAuthToken activeToken() {
		return active.get();
	}

	/**
	 * The check a relay must pass immediately before broadcasting. It is strict
	 * about order: <b>no native prepared-transaction value is read until the full
	 * process-local ownership check has passed</b>, so a stale authorization
	 * never dereferences a foreign or disposed native object. The order is
	 * lifetime and session and ownership and disposal (all pure Java, zero native
	 * calls), then the native re-read, then the same checks again because state
	 * can change during inspection, then a constant-time fingerprint comparison
	 * against the authorized value ({@link XmrError#TRANSACTION_MUTATED} on any
	 * discrepancy), then a single-use consumption. Any failure invalidates the
	 * authorization. This method never relays.
	 */
	public void validateForRelay(XmrAuthToken token, XmrSendSnapshot snapshot,
			MoneroEngine.Prepared prepared, MoneroEngine.Session session,
			Object flowToken) throws XmrError.XmrException {
		if (!token.isLive(clock.nowMonotonicMs())) {
			token.invalidate();
			throw new XmrError.XmrException(XmrError.AUTHORIZATION_INVALID);
		}
		requireLiveSessionAndOwnership(token, snapshot, prepared, session,
				flowToken);

		long amount = prepared.amountAtomic();
		long fee = prepared.feeAtomic();
		long dust = prepared.dustAtomic();
		long count = prepared.txCount();
		List<String> ids = prepared.txIds();

		requireLiveSessionAndOwnership(token, snapshot, prepared, session,
				flowToken);

		byte[] recomputed;
		try {
			XmrSendSnapshot live = XmrSendSnapshot.create(snapshot.walletId(),
					snapshot.primaryWalletFingerprint(), snapshot.network(),
					snapshot.destinationExact(), snapshot.destinationKind(),
					amount, fee, dust, count, ids);
			recomputed = live.fingerprint();
		} catch (XmrError.XmrException nativeInconsistent) {
			token.invalidate();
			throw new XmrError.XmrException(XmrError.TRANSACTION_MUTATED,
					nativeInconsistent);
		}
		if (!snapshot.fingerprintEquals(recomputed)) {
			token.invalidate();
			throw new XmrError.XmrException(XmrError.TRANSACTION_MUTATED);
		}

		long epoch = guard.sessionEpoch();
		long lockGen = guard.lockGeneration();
		boolean ok = token.consume(clock.nowMonotonicMs(),
				snapshot.fingerprint(), prepared, session, snapshot.walletId(),
				epoch, lockGen, flowToken);
		if (!ok) {
			throw new XmrError.XmrException(XmrError.AUTHORIZATION_INVALID);
		}
	}

	/**
	 * Verify the live session and the full ownership without touching the native
	 * transaction. {@link MoneroEngine.Prepared#isDisposed()} is a pure Java flag
	 * read, so this makes zero native calls. Throws and invalidates on any
	 * mismatch.
	 */
	private void requireLiveSessionAndOwnership(XmrAuthToken token,
			XmrSendSnapshot snapshot, MoneroEngine.Prepared prepared,
			MoneroEngine.Session session, Object flowToken)
			throws XmrError.XmrException {
		long epoch = guard.sessionEpoch();
		long lockGen = guard.lockGeneration();
		String currentWallet = guard.currentWalletId();
		if (!guard.sessionValid() || currentWallet == null
				|| !snapshot.walletId().equals(currentWallet)) {
			token.invalidate();
			throw new XmrError.XmrException(XmrError.SESSION_INVALIDATED);
		}
		if (!token.bindsTo(prepared, session, currentWallet, epoch, lockGen,
				flowToken) || prepared.isDisposed()) {
			token.invalidate();
			throw new XmrError.XmrException(XmrError.AUTHORIZATION_INVALID);
		}
	}

	private static XmrError wrongOrEmpty(@Nullable char[] password) {
		return (password == null || password.length == 0)
				? XmrError.EMPTY_PASSWORD : XmrError.WRONG_PASSWORD;
	}
}
