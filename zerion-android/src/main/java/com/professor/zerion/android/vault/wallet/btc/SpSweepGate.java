package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class SpSweepGate {

	@Nullable
	private volatile BtcWallet.SpSweepPlan pending;
	@Nullable
	private volatile String pendingWalletId;

	public synchronized void prepare(BtcWallet.SpSweepPlan plan, String walletId) {
		this.pendingWalletId = walletId;
		this.pending = plan;
	}

	public synchronized void clear() {
		this.pending = null;
		this.pendingWalletId = null;
	}

	@Nullable
	public synchronized BtcWallet.SpSweepPlan pending() {
		return pending;
	}

	public synchronized BtcWallet.SpSweepPlan authorize(String reviewedFingerprint,
			boolean authenticated, @Nullable String walletId)
			throws SendGate.AuthorizationException {
		BtcWallet.SpSweepPlan p = pending;
		String owner = pendingWalletId;
		if (p == null) {
			throw new SendGate.AuthorizationException(
					"no sweep to authorize");
		}
		if (owner == null || !owner.equals(walletId)) {
			clear();
			throw new SendGate.AuthorizationException(
					"the wallet changed; review again");
		}
		if (!p.fingerprint.equals(reviewedFingerprint)) {
			clear();
			throw new SendGate.AuthorizationException(
					"the sweep changed; review again");
		}
		if (!authenticated) {
			throw new SendGate.AuthorizationException("authentication failed");
		}
		clear();
		return p;
	}
}
