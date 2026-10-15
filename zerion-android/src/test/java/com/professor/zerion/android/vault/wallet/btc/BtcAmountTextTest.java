package com.professor.zerion.android.vault.wallet.btc;

import org.junit.Test;

import static com.professor.zerion.android.vault.wallet.btc.BtcAmountText.UNIT_BTC;
import static com.professor.zerion.android.vault.wallet.btc.BtcAmountText.UNIT_EUR;
import static com.professor.zerion.android.vault.wallet.btc.BtcAmountText.UNIT_USD;
import static org.junit.Assert.assertEquals;

public class BtcAmountTextTest {

	private static final BtcPrice.Rates RATES = BtcPrice.Rates.fromJson(
			"{\"EUR\":50000.0,\"USD\":40000.0,\"JPY\":7000000.0}");

	@Test
	public void bitcoinInputUsesTheExactParser() {
		assertEquals(BtcAmounts.parseBtc("0.1"),
				BtcAmountText.toSats(UNIT_BTC, "0.1", null));
		assertEquals(-1L, BtcAmountText.toSats(UNIT_BTC, "21000001", RATES));
	}

	@Test
	public void fiatInputNeedsARateAndNeverExceedsTheMoneySupply() {
		assertEquals(-1L, BtcAmountText.toSats(UNIT_EUR, "1", null));
		assertEquals(200_000L, BtcAmountText.toSats(UNIT_EUR, "100", RATES));
		assertEquals(250_000L, BtcAmountText.toSats(UNIT_USD, "100", RATES));
		assertEquals(-1L, BtcAmountText.toSats(UNIT_EUR, "1e30", RATES));
		assertEquals(0L, BtcAmountText.toSats(UNIT_EUR, "0", RATES));
	}

	@Test
	public void theEquivalentLineFollowsTheDisplayCurrency() {
		assertEquals("≈ ¥700000.00",
				BtcAmountText.equivalent(UNIT_BTC, "0.1", RATES, "JPY"));
		assertEquals("", BtcAmountText.equivalent(UNIT_BTC, "0.1", RATES,
				"GBP"));
		assertEquals("", BtcAmountText.equivalent(UNIT_EUR, null, RATES,
				"EUR"));
	}

	@Test
	public void formattingIsLocaleIndependent() {
		java.util.Locale saved = java.util.Locale.getDefault();
		try {
			java.util.Locale.setDefault(java.util.Locale.GERMANY);
			assertEquals("≈ 0.00020000 BTC",
					BtcAmountText.equivalent(UNIT_EUR, "10", RATES, "EUR"));
			assertEquals("1.5 BTC", BtcAmountText.formatBtc(150_000_000L));
		} finally {
			java.util.Locale.setDefault(saved);
		}
	}
}
