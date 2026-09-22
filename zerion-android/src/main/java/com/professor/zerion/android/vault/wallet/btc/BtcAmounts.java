package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Bounds for amounts in satoshis. Every amount that reaches the planner
 * comes through here: it must be positive and at most the total supply, so
 * a parsed value cannot overflow the fee arithmetic or reach the library's
 * own checks as a negative or absurd number.
 */
@NotNullByDefault
public final class BtcAmounts {

	public static final long MAX_MONEY_SAT = 21_000_000L * 100_000_000L;
	private static final BigDecimal MAX_BTC = new BigDecimal("21000000");
	private static final BigDecimal ONE_SAT = new BigDecimal("0.00000001");

	private BtcAmounts() {
	}

	/** True for an amount a transaction output can carry. */
	public static boolean sendable(long sat) {
		return sat > 0 && sat <= MAX_MONEY_SAT;
	}

	/** The amount itself when it is within bounds, otherwise -1. */
	public static long bounded(long sat) {
		return sat < 0 || sat > MAX_MONEY_SAT ? -1 : sat;
	}

	/**
	 * Parses a decimal bitcoin amount into satoshis, truncating anything
	 * beyond eight decimals, or -1 when the text is not a number, is
	 * negative, or exceeds the total supply. The magnitude is compared
	 * before any rescaling, so an exponent like 1e999999999 in the text is
	 * refused without expanding it into memory.
	 */
	public static long parseBtc(String text) {
		try {
			BigDecimal v = new BigDecimal(text.trim());
			if (v.signum() < 0) return -1;
			if (v.compareTo(MAX_BTC) > 0) return -1;
			if (v.signum() > 0 && v.compareTo(ONE_SAT) < 0) return 0;
			long sat = v.movePointRight(8).setScale(0, RoundingMode.DOWN)
					.longValueExact();
			return bounded(sat);
		} catch (RuntimeException e) {
			return -1;
		}
	}
}
