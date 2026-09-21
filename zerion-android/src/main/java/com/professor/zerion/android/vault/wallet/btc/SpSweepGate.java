package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Holds one planned Silent Payments sweep between review and authorisation
 * and releases it only for the fingerprint the user reviewed, so the
 * transaction that is signed is the one that was shown.
 */
@NotNullByDefault
public final class SpSweepGate {

	@Nullable
	private volatile BtcWallet.SpSweepPlan pending;

	public void prepare(BtcWallet.SpSweepPlan plan) {
		this.pending = plan;
	}

	public void clear() {
		this.pending = null;
	}

	@Nullable
	public BtcWallet.SpSweepPlan pending() {
		return pending;
	}

	public BtcWallet.SpSweepPlan authorize(String reviewedFingerprint,
			boolean authenticated) throws SendGate.AuthorizationException {
		BtcWallet.SpSweepPlan p = pending;
		if (p == null) {
			throw new SendGate.AuthorizationException(
					"no sweep to authorize");
		}
		if (!p.fingerprint.equals(reviewedFingerprint)) {
			pending = null;
			throw new SendGate.AuthorizationException(
					"the sweep changed; review again");
		}
		if (!authenticated) {
			throw new SendGate.AuthorizationException("authentication failed");
		}
		pending = null;
		return p;
	}
}
