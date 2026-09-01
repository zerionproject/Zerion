package com.professor.zerion.android.vault.wallet.btc;

import com.professor.zerion.android.vault.wallet.btc.payjoin.PayjoinSession;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

/**
 * Decides what happens after a Payjoin exchange. There is no path that sends a
 * transaction: a successful, fully accepted proposal goes to review, and every
 * failure offers an explicit normal-send fallback or aborts. A Payjoin failure
 * never silently becomes a normal payment.
 */
@NotNullByDefault
public final class PayjoinOutcomeRouter {

	public enum Action {
		REVIEW_PAYJOIN,
		OFFER_NORMAL_FALLBACK,
		ABORT
	}

	private PayjoinOutcomeRouter() {
	}

	public static Action route(PayjoinSession.Status status,
			@Nullable PayjoinSender.Review review) {
		if (status == PayjoinSession.Status.VALIDATED) {
			if (review != null && review.ok) {
				return Action.REVIEW_PAYJOIN;
			}
			return Action.OFFER_NORMAL_FALLBACK;
		}
		if (status == PayjoinSession.Status.REPLAYED) {
			return Action.ABORT;
		}
		return Action.OFFER_NORMAL_FALLBACK;
	}
}
