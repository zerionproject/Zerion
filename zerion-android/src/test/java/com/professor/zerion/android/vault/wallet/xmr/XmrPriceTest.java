package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class XmrPriceTest {

	private static final String KRAKEN_USD =
			"{\"error\":[],\"result\":{\"XXMRZUSD\":{\"a\":[\"163.0\",\"1\","
			+ "\"1.0\"],\"b\":[\"162.9\",\"2\",\"2.0\"],\"c\":[\"162.95000\","
			+ "\"0.10000000\"],\"v\":[\"100\",\"200\"]}}}";

	@Test
	public void parsesKrakenLastTradePrice() {
		assertEquals(162.95, XmrPrice.parseKrakenLast(KRAKEN_USD), 0.00001);
	}

	@Test
	public void krakenErrorResponseYieldsZero() {
		String err = "{\"error\":[\"EQuery:Unknown asset pair\"],\"result\":{}}";
		assertEquals(0, XmrPrice.parseKrakenLast(err), 0.0);
	}

	@Test
	public void garbageYieldsZero() {
		assertEquals(0, XmrPrice.parseKrakenLast("not json"), 0.0);
		assertEquals(0, XmrPrice.parseKrakenLast(""), 0.0);
	}

	@Test
	public void ratesRoundTripThroughJson() {
		XmrPrice.Rates r = new XmrPrice.Rates(162.95, 150.20);
		XmrPrice.Rates back = XmrPrice.Rates.fromJson(r.toJson());
		assertEquals(162.95, back.usd, 0.001);
		assertEquals(150.20, back.eur, 0.001);
		assertTrue(back.has("USD"));
		assertTrue(back.has("EUR"));
		assertFalse(back.isEmpty());
	}

	@Test
	public void negativeAndZeroRatesAreTreatedAsAbsent() {
		XmrPrice.Rates r = new XmrPrice.Rates(-5, 0);
		assertEquals(0, r.usd, 0.0);
		assertEquals(0, r.eur, 0.0);
		assertTrue(r.isEmpty());
		assertFalse(r.has("USD"));
	}

	@Test
	public void getSelectsCurrency() {
		XmrPrice.Rates r = new XmrPrice.Rates(162.95, 150.20);
		assertEquals(162.95, r.get("USD"), 0.001);
		assertEquals(150.20, r.get("EUR"), 0.001);
	}
}
