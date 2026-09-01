package com.professor.zerion.android.vault.wallet.btc.payjoin;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Production gate for the Payjoin feature. It stays disabled until every
 * remaining P-4C gate is met: the native instrumentation is green on device,
 * armv7 native loading is verified, the BIP77 directory/relay is vetted live
 * over Tor, and funded testing is explicitly approved. There is no runtime
 * override or hidden bypass; enabling requires changing this constant in a
 * reviewed build.
 */
@NotNullByDefault
public final class PayjoinFeature {

	public static final boolean PRODUCTION_ENABLED = false;

	private PayjoinFeature() {
	}

	public static boolean isEnabled() {
		return PRODUCTION_ENABLED;
	}
}
