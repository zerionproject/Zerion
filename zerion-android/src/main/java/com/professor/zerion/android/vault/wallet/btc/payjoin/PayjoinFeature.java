package com.professor.zerion.android.vault.wallet.btc.payjoin;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class PayjoinFeature {

	public static final boolean PRODUCTION_ENABLED = false;

	private PayjoinFeature() {
	}

	public static boolean isEnabled() {
		return PRODUCTION_ENABLED;
	}
}
