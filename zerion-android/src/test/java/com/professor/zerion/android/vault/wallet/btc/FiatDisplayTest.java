package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FiatDisplayTest {

	private static BtcPrice.Rates rates(double eur) {
		return BtcPrice.Rates.fromJson("{\"EUR\":" + eur + ",\"USD\":" + (eur + 5)
				+ "}");
	}

	@Test
	public void freshPriceFormatsConversion() {
		String line = FiatDisplay.line(23763, "EUR", "€", rates(50000));
		assertEquals("≈ €11.88  EUR", line);
	}

	@Test
	public void missingRatesShowsPlaceholderNotEmpty() {
		String line = FiatDisplay.line(23763, "EUR", "€", null);
		assertEquals("≈ — EUR", line);
		assertFalse(line.isEmpty());
	}

	@Test
	public void currencyWithoutPriceShowsPlaceholder() {
		String line = FiatDisplay.line(100000, "JPY", "¥", rates(50000));
		assertEquals("≈ — JPY", line);
	}

	@Test
	public void unknownBalanceShowsPlaceholderNotEmpty() {
		String line = FiatDisplay.line(-1, "EUR", "€", rates(50000));
		assertEquals("≈ — EUR", line);
		assertFalse(line.isEmpty());
	}

	@Test
	public void lineIsNeverEmpty() {
		assertNotNull(FiatDisplay.line(-1, "USD", "$", null));
		assertFalse(FiatDisplay.line(-1, "USD", "$", null).isEmpty());
		assertFalse(FiatDisplay.line(0, "USD", "$", rates(0)).isEmpty());
	}

	@Test
	public void currencySwitchUsesSameCachedRates() {
		BtcPrice.Rates r = rates(50000);
		assertEquals("≈ €5.00  EUR",
				FiatDisplay.line(10000, "EUR", "€", r));
		assertEquals("≈ $5.00  USD",
				FiatDisplay.line(10000, "USD", "$", r));
	}

	@Test
	public void staleWhenOlderThanTtlOrNeverFetched() {
		assertTrue(FiatDisplay.isStale(0, 1_000_000, 1000));
		assertTrue(FiatDisplay.isStale(1000, 5000, 1000));
		assertFalse(FiatDisplay.isStale(4500, 5000, 1000));
	}
}
