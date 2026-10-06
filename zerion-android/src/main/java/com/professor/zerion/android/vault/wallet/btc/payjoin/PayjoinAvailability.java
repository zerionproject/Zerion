package com.professor.zerion.android.vault.wallet.btc.payjoin;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class PayjoinAvailability {

	private PayjoinAvailability() {
	}

	public static boolean canOffer(String bip21) {
		return PayjoinFeature.isEnabled() && PayjoinUri.detect(bip21).isPayjoin();
	}
}
