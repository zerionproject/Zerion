package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Holds the one reviewed plan between review and authorization. A plan is
 * released for signing only against the fingerprint the user reviewed, a
 * fresh authentication, and the wallet it was prepared for: a wallet
 * opened in between clears the plan rather than signing it with another
 * wallet's journal, reservation and isolation.
 */
@NotNullByDefault
public final class SendGate {

	public static final class AuthorizationException extends Exception {
		public AuthorizationException(String message) {
			super(message);
		}
	}

	@Nullable
	private volatile BtcWallet.SendPlan pending;
	@Nullable
	private volatile String pendingWalletId;

	public synchronized void prepare(BtcWallet.SendPlan plan, String walletId) {
		this.pendingWalletId = walletId;
		this.pending = plan;
	}

	public synchronized void clear() {
		this.pending = null;
		this.pendingWalletId = null;
	}

	@Nullable
	public synchronized BtcWallet.SendPlan pending() {
		return pending;
	}

	public synchronized BtcWallet.SendPlan authorize(String reviewedFingerprint,
			boolean authenticated, @Nullable String walletId)
			throws AuthorizationException {
		BtcWallet.SendPlan p = pending;
		String owner = pendingWalletId;
		if (p == null) {
			throw new AuthorizationException("no transaction to authorize");
		}
		if (owner == null || !owner.equals(walletId)) {
			clear();
			throw new AuthorizationException(
					"the wallet changed; review again");
		}
		if (!p.fingerprint.equals(reviewedFingerprint)) {
			clear();
			throw new AuthorizationException(
					"the transaction changed; review again");
		}
		if (!authenticated) {
			throw new AuthorizationException("authentication failed");
		}
		clear();
		return p;
	}
}
