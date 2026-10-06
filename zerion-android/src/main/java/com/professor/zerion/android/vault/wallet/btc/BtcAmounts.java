package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.math.BigDecimal;
import java.math.RoundingMode;

@NotNullByDefault
public final class BtcAmounts {

	public static final long MAX_MONEY_SAT = 21_000_000L * 100_000_000L;
	private static final BigDecimal MAX_BTC = new BigDecimal("21000000");
	private static final BigDecimal ONE_SAT = new BigDecimal("0.00000001");

	private BtcAmounts() {
	}

	public static boolean sendable(long sat) {
		return sat > 0 && sat <= MAX_MONEY_SAT;
	}

	public static long bounded(long sat) {
		return sat < 0 || sat > MAX_MONEY_SAT ? -1 : sat;
	}

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
