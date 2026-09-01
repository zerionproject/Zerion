package com.professor.zerion.android.vault.wallet.btc.payjoin;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class PayjoinFeatureTest {

	private static final String PJ_URI = "bitcoin:"
			+ "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4"
			+ "?pj=https://payjo.in/ABC123";

	@Test
	public void productionGateIsDisabled() {
		assertFalse(PayjoinFeature.isEnabled());
		assertFalse(PayjoinFeature.PRODUCTION_ENABLED);
	}

	@Test
	public void payjoinNotOfferedWhileGateDisabled() {
		assertFalse(PayjoinAvailability.canOffer(PJ_URI));
	}

	@Test
	public void gateDisabledOverridesValidPayjoinUri() {
		PayjoinUri parsed = PayjoinUri.detect(PJ_URI);
		assertFalse(PayjoinFeature.isEnabled() && parsed.isPayjoin()
				&& PayjoinAvailability.canOffer(PJ_URI));
	}
}
