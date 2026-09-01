package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class SendGate {

	public static final class AuthorizationException extends Exception {
		public AuthorizationException(String message) {
			super(message);
		}
	}

	@Nullable
	private volatile BtcWallet.SendPlan pending;

	public void prepare(BtcWallet.SendPlan plan) {
		this.pending = plan;
	}

	public void clear() {
		this.pending = null;
	}

	@Nullable
	public BtcWallet.SendPlan pending() {
		return pending;
	}

	public BtcWallet.SendPlan authorize(String reviewedFingerprint,
			boolean authenticated) throws AuthorizationException {
		BtcWallet.SendPlan p = pending;
		if (p == null) {
			throw new AuthorizationException("no transaction to authorize");
		}
		if (!p.fingerprint.equals(reviewedFingerprint)) {
			pending = null;
			throw new AuthorizationException(
					"the transaction changed; review again");
		}
		if (!authenticated) {
			throw new AuthorizationException("authentication failed");
		}
		pending = null;
		return p;
	}
}
