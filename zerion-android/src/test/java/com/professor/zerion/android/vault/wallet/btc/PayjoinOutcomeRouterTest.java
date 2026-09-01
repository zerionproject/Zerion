package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.professor.zerion.android.vault.wallet.btc.payjoin.PayjoinSession;
import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyAnalyzer;

import org.junit.Test;

import java.util.LinkedHashSet;

public class PayjoinOutcomeRouterTest {

	private static PayjoinSender.Review review(boolean ok) {
		return new PayjoinSender.Review(ok,
				ok ? PayjoinSender.Reject.OK
						: PayjoinSender.Reject.OWNED_SET_CHANGED,
				null, PrivacyAnalyzer.Analysis.unavailable(),
				new LinkedHashSet<>(), new LinkedHashSet<>());
	}

	@Test
	public void validatedAndAcceptedGoesToReview() {
		assertEquals(PayjoinOutcomeRouter.Action.REVIEW_PAYJOIN,
				PayjoinOutcomeRouter.route(PayjoinSession.Status.VALIDATED,
						review(true)));
	}

	@Test
	public void validatedButRejectedOffersFallback() {
		assertEquals(PayjoinOutcomeRouter.Action.OFFER_NORMAL_FALLBACK,
				PayjoinOutcomeRouter.route(PayjoinSession.Status.VALIDATED,
						review(false)));
	}

	@Test
	public void failedOffersFallback() {
		assertEquals(PayjoinOutcomeRouter.Action.OFFER_NORMAL_FALLBACK,
				PayjoinOutcomeRouter.route(PayjoinSession.Status.FAILED, null));
	}

	@Test
	public void rejectedOffersFallback() {
		assertEquals(PayjoinOutcomeRouter.Action.OFFER_NORMAL_FALLBACK,
				PayjoinOutcomeRouter.route(PayjoinSession.Status.REJECTED, null));
	}

	@Test
	public void replayedAborts() {
		assertEquals(PayjoinOutcomeRouter.Action.ABORT,
				PayjoinOutcomeRouter.route(PayjoinSession.Status.REPLAYED, null));
	}

	@Test
	public void failureNeverProceedsToReview() {
		for (PayjoinSession.Status s : new PayjoinSession.Status[]{
				PayjoinSession.Status.FAILED, PayjoinSession.Status.REJECTED,
				PayjoinSession.Status.REPLAYED}) {
			assertNotEquals(PayjoinOutcomeRouter.Action.REVIEW_PAYJOIN,
					PayjoinOutcomeRouter.route(s, null));
		}
	}
}
