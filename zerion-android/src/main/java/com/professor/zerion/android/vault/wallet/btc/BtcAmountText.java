package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.math.BigDecimal;
import java.util.Locale;

import javax.annotation.Nullable;

@NotNullByDefault
public final class BtcAmountText {

	public static final int UNIT_BTC = 0;
	public static final int UNIT_EUR = 1;
	public static final int UNIT_USD = 2;

	private BtcAmountText() {
	}

	public static String unitName(int unit) {
		return unit == UNIT_EUR ? "EUR" : unit == UNIT_USD ? "USD" : "BTC";
	}

	private static double priceForUnit(int unit,
			@Nullable BtcPrice.Rates rates) {
		if (rates == null) {
			return 0;
		}
		return unit == UNIT_EUR ? rates.eur : rates.usd;
	}

	public static long toSats(int unit, String typed,
			@Nullable BtcPrice.Rates rates) {
		try {
			if (unit == UNIT_BTC) {
				return BtcAmounts.parseBtc(typed);
			}
			BigDecimal v = new BigDecimal(typed.trim());
			if (v.signum() < 0) {
				return -1;
			}
			double price = priceForUnit(unit, rates);
			if (price <= 0) {
				return -1;
			}
			return BtcAmounts.bounded(
					(long) Math.floor(v.doubleValue() / price * 1e8));
		} catch (Exception e) {
			return -1;
		}
	}

	public static String equivalent(int unit, @Nullable String typed,
			@Nullable BtcPrice.Rates rates, String fiatCurrency) {
		if (typed == null || typed.trim().isEmpty()) {
			return "";
		}
		double val;
		try {
			val = Double.parseDouble(typed.trim());
		} catch (Exception e) {
			return "";
		}
		if (unit == UNIT_BTC) {
			double price = rates == null ? 0 : rates.get(fiatCurrency);
			if (price <= 0) {
				return "";
			}
			return "≈ " + symbolFor(fiatCurrency)
					+ String.format(Locale.US, "%.2f", val * price);
		}
		double price = priceForUnit(unit, rates);
		if (price <= 0) {
			return "";
		}
		return "≈ " + String.format(Locale.US, "%.8f", val / price)
				+ " BTC";
	}

	public static String symbolFor(String currency) {
		switch (currency) {
			case "USD":
				return "$";
			case "GBP":
				return "£";
			case "JPY":
				return "¥";
			case "CAD":
				return "CA$";
			case "AUD":
				return "A$";
			case "CHF":
				return "CHF ";
			case "EUR":
				return "€";
			default:
				return currency + " ";
		}
	}

	public static String formatBtc(long sat) {
		if (sat == 0) {
			return "0 BTC";
		}
		BigDecimal btc = BigDecimal.valueOf(sat).movePointLeft(8)
				.stripTrailingZeros();
		return btc.toPlainString() + " BTC";
	}
}
