package com.professor.zerion.android.vault.wallet.btc;

import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyAnalyzer;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * The exact final Payjoin transaction presented for review. It corresponds to a
 * single canonical PayjoinFinalTx fingerprint; authentication is bound to that
 * same fingerprint, so what the user sees is what gets signed.
 */
@NotNullByDefault
public final class PayjoinReviewData {

	public final String recipient;
	public final long amountSat;
	public final long feeSat;
	public final long totalSat;
	public final boolean payjoin;
	public final PrivacyAnalyzer.Analysis analysis;
	public final String fingerprint;

	public PayjoinReviewData(String recipient, long amountSat, long feeSat,
			long totalSat, PrivacyAnalyzer.Analysis analysis,
			String fingerprint) {
		this.recipient = recipient;
		this.amountSat = amountSat;
		this.feeSat = feeSat;
		this.totalSat = totalSat;
		this.payjoin = true;
		this.analysis = analysis;
		this.fingerprint = fingerprint;
	}

	public static PayjoinReviewData from(PayjoinSender.Review review,
			String recipient, long amountSat) {
		PayjoinFinalTx tx = review.finalTx;
		if (tx == null) {
			throw new IllegalStateException("review has no final transaction");
		}
		return new PayjoinReviewData(recipient, amountSat, tx.feeSat,
				amountSat + tx.feeSat, review.analysis, tx.fingerprint());
	}
}
