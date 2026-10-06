package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@NotNullByDefault
public final class XmrSendGate {

	private static final long DEFAULT_TTL_MS = 30_000;

	public interface SendGuard {
		long sessionEpoch();

		long lockGeneration();

		boolean sessionValid();

		@Nullable
		String currentWalletId();
	}

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

	public void invalidateActive() {
		XmrAuthToken previous = active.getAndSet(null);
		if (previous != null) previous.invalidate();
	}

	@Nullable
	XmrAuthToken activeToken() {
		return active.get();
	}

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
