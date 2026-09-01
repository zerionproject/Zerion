package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BtcPriceTest {

	private static final String MEMPOOL_JSON =
			"{\"time\":1735000000,\"USD\":66000.5,\"EUR\":61000.25,"
					+ "\"GBP\":52000,\"CAD\":90000,\"CHF\":58000,"
					+ "\"AUD\":100000,\"JPY\":9500000}";

	@Test
	public void parsesEurAndUsdFromMempoolResponse() {
		BtcPrice.Rates r = BtcPrice.Rates.fromJson(MEMPOOL_JSON);
		assertTrue(r.has("EUR"));
		assertTrue(r.has("USD"));
		assertEquals(61000.25, r.get("EUR"), 0.001);
		assertEquals(66000.5, r.get("USD"), 0.001);
	}

	@Test
	public void currencySwitchReadsTheRightRate() {
		BtcPrice.Rates r = BtcPrice.Rates.fromJson(MEMPOOL_JSON);
		assertEquals(61000.25, r.get("EUR"), 0.001);
		assertEquals(66000.5, r.get("USD"), 0.001);
		assertEquals("unknown currency yields no rate", 0, r.get("XYZ"), 0.0);
	}

	@Test
	public void malformedOrChallengeBodyYieldsEmptyRates() {
		BtcPrice.Rates challenge = BtcPrice.Rates.fromJson(
				"<html><title>Attention Required! | Cloudflare</title></html>");
		assertTrue("an HTML challenge must not be read as a price",
				challenge.isEmpty());
		assertFalse(challenge.has("EUR"));
	}

	@Test
	public void outOfBoundsValuesAreRejected() {
		assertTrue(BtcPrice.Rates.fromJson("{\"EUR\":-5}").isEmpty());
		assertTrue(BtcPrice.Rates.fromJson("{\"EUR\":0}").isEmpty());
		assertTrue(BtcPrice.Rates.fromJson("{\"EUR\":9999999999999}")
				.isEmpty());
	}

	@Test
	public void implausiblyLowRatesAreRejectedSoFiatEntryCannotInflateSends() {
		assertTrue(BtcPrice.Rates.fromJson("{\"EUR\":1}").isEmpty());
		assertTrue(BtcPrice.Rates.fromJson("{\"EUR\":99.9}").isEmpty());
		assertTrue(BtcPrice.Rates.fromJson("{\"EUR\":100}").has("EUR"));
	}

	@Test
	public void ratesSurviveJsonRoundTripForCacheRestore() {
		BtcPrice.Rates original = BtcPrice.Rates.fromJson(MEMPOOL_JSON);
		BtcPrice.Rates restored = BtcPrice.Rates.fromJson(original.toJson());
		assertEquals(original.get("EUR"), restored.get("EUR"), 0.001);
		assertEquals(original.get("USD"), restored.get("USD"), 0.001);
	}

	@Test
	public void freshCacheIsNotStaleAndOldCacheIsStale() {
		long ttl = 15L * 60L * 1000L;
		long now = 10_000_000L;
		assertFalse("just fetched is fresh",
				FiatDisplay.isStale(now - 1000, now, ttl));
		assertTrue("older than the TTL is stale",
				FiatDisplay.isStale(now - ttl - 1, now, ttl));
	}

	@Test
	public void lineShowsPlaceholderWhenNoRateButValueWhenPresent() {
		BtcPrice.Rates empty = BtcPrice.Rates.fromJson("{}");
		assertFalse(FiatDisplay.hasPrice(empty, "EUR"));
		String noRate = FiatDisplay.line(18281, "EUR", "€", empty);
		assertTrue("no rate must render the honest placeholder",
				noRate.contains(FiatDisplay.UNAVAILABLE_VALUE));

		BtcPrice.Rates r = BtcPrice.Rates.fromJson(MEMPOOL_JSON);
		assertTrue(FiatDisplay.hasPrice(r, "EUR"));
		String withRate = FiatDisplay.line(100_000_000L, "EUR", "€", r);
		assertFalse("a real rate must not show the placeholder",
				withRate.contains(FiatDisplay.UNAVAILABLE_VALUE));
	}
}
