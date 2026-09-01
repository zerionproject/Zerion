package com.professor.zerion.android.vault.wallet.btc.payjoin;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Single decision point for whether the UI may offer Payjoin for a request.
 * Payjoin can be offered only when the production gate is enabled and the
 * request actually carries a usable Payjoin endpoint. With the gate disabled it
 * is never offered, regardless of the request.
 */
@NotNullByDefault
public final class PayjoinAvailability {

	private PayjoinAvailability() {
	}

	public static boolean canOffer(String bip21) {
		return PayjoinFeature.isEnabled() && PayjoinUri.detect(bip21).isPayjoin();
	}
}
