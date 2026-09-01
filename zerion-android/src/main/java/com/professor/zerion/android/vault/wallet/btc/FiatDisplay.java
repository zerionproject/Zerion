package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;

import javax.annotation.Nullable;

/**
 * Deterministic fiat line for the balance. The row is always present: with a
 * usable price it shows the conversion, and without one it shows an honest
 * unavailable placeholder rather than an empty string, so the layout never
 * moves and the value never silently disappears when a price refresh is
 * temporarily unavailable. No price is ever fabricated.
 */
@NotNullByDefault
public final class FiatDisplay {

	public static final String UNAVAILABLE_VALUE = "—";

	private FiatDisplay() {
	}

	public static boolean hasPrice(@Nullable BtcPrice.Rates rates,
			String currency) {
		return rates != null && rates.get(currency) > 0;
	}

	public static String line(long sat, String currency, String symbol,
			@Nullable BtcPrice.Rates rates) {
		if (sat < 0 || !hasPrice(rates, currency)) {
			return "≈ " + UNAVAILABLE_VALUE + " " + currency;
		}
		double fiat = (sat / 1e8) * rates.get(currency);
		return "≈ " + symbol
				+ String.format(Locale.US, "%.2f", fiat) + "  " + currency;
	}

	public static boolean isStale(long fetchedAtMillis, long nowMillis,
			long maxAgeMillis) {
		return fetchedAtMillis <= 0 || nowMillis - fetchedAtMillis > maxAgeMillis;
	}
}
