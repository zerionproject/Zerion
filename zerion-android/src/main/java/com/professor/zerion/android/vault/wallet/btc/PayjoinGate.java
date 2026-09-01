package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Single-use authorization for a Payjoin final transaction, bound to the exact
 * transaction fingerprint the user reviewed. A fingerprint mismatch means the
 * transaction changed after review: authorization is discarded and a fresh
 * review is required. Authentication is consumed once, so a signed proposal can
 * never be replayed.
 */
@NotNullByDefault
public final class PayjoinGate {

	public static final class AuthorizationException extends Exception {
		public AuthorizationException(String message) {
			super(message);
		}
	}

	@Nullable
	private PayjoinFinalTx pending;
	@Nullable
	private String pendingFingerprint;

	public void prepare(PayjoinFinalTx tx) {
		this.pending = tx;
		this.pendingFingerprint = tx.fingerprint();
	}

	public void clear() {
		this.pending = null;
		this.pendingFingerprint = null;
	}

	@Nullable
	public PayjoinFinalTx pending() {
		return pending;
	}

	public PayjoinFinalTx authorize(String reviewedFingerprint,
			boolean authenticated) throws AuthorizationException {
		PayjoinFinalTx p = pending;
		String fp = pendingFingerprint;
		if (p == null || fp == null) {
			throw new AuthorizationException("no transaction to authorize");
		}
		if (!fp.equals(reviewedFingerprint)) {
			clear();
			throw new AuthorizationException(
					"the transaction changed; review again");
		}
		if (!fp.equals(p.fingerprint())) {
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
