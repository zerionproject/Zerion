package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BtcPriceRatesTest {

	@Test
	public void jsonRoundTripPreservesRates() {
		BtcPrice.Rates r = BtcPrice.Rates.fromJson(
				"{\"EUR\":48000.5,\"USD\":52000.25,\"GBP\":41000.0}");
		BtcPrice.Rates back = BtcPrice.Rates.fromJson(r.toJson());
		assertEquals(48000.5, back.get("EUR"), 0.001);
		assertEquals(52000.25, back.get("USD"), 0.001);
		assertEquals(41000.0, back.get("GBP"), 0.001);
		assertFalse(back.isEmpty());
	}

	@Test
	public void garbageJsonYieldsEmptyRates() {
		assertTrue(BtcPrice.Rates.fromJson("not json").isEmpty());
		assertTrue(BtcPrice.Rates.fromJson("{}").isEmpty());
		assertEquals(0, BtcPrice.Rates.fromJson("{}").get("EUR"), 0.0);
	}

	@Test
	public void nonFiniteOrAbsurdPricesRejected() {
		assertTrue(BtcPrice.Rates.fromJson(
				"{\"EUR\":999999999999999}").isEmpty());
		assertFalse(BtcPrice.Rates.fromJson("{\"EUR\":50000}").isEmpty());
	}

	@Test
	public void zeroOrNegativePricesDropped() {
		BtcPrice.Rates r = BtcPrice.Rates.fromJson(
				"{\"EUR\":0,\"USD\":50000}");
		assertFalse(r.has("EUR"));
		assertTrue(r.has("USD"));
	}
}
