package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * The URI parser extracts the address and amount but never judges address
 * validity, and it converts XMR amounts to atomic units with exact arithmetic,
 * rejecting anything malformed, over-precise or overflowing.
 */
public class MoneroUriTest {

	private static final String ADDR =
			"42ey1afDFnn4886T7196doS9GPMzexD9gXpsZJDwVjeRVdFCSoHnv7KPbBeGpzJBzHRCAs9UxqeoyFQMYbqSWYTfJJQAWDm";

	@Test
	public void bareAddressIsAcceptedWithNoAmount() throws Exception {
		MoneroUri u = MoneroUri.parse(ADDR);
		assertEquals(ADDR, u.address());
		assertFalse(u.hasAmount());
		assertEquals(-1, u.amountAtomic());
	}

	@Test
	public void uriWithAmountParses() throws Exception {
		MoneroUri u = MoneroUri.parse("monero:" + ADDR + "?tx_amount=1.5");
		assertEquals(ADDR, u.address());
		assertTrue(u.hasAmount());
		assertEquals(1_500_000_000_000L, u.amountAtomic());
	}

	@Test
	public void uriWithoutAmountHasNoAmount() throws Exception {
		MoneroUri u = MoneroUri.parse(
				"monero:" + ADDR + "?recipient_name=Alice");
		assertEquals(ADDR, u.address());
		assertFalse(u.hasAmount());
	}

	@Test
	public void addressIsNotValidatedByTheParser() throws Exception {
		MoneroUri u = MoneroUri.parse("monero:not-a-valid-address?tx_amount=1");
		assertEquals("the raw address is handed back verbatim for the Monero "
				+ "parser to judge", "not-a-valid-address", u.address());
	}

	@Test
	public void amountArithmeticIsExact() throws Exception {
		assertEquals(0L, MoneroUri.parseXmrToAtomic("0"));
		assertEquals(1L, MoneroUri.parseXmrToAtomic("0.000000000001"));
		assertEquals(1_000_000_000_000L, MoneroUri.parseXmrToAtomic("1"));
		assertEquals(1_230_000_000_000L, MoneroUri.parseXmrToAtomic("1.23"));
		assertEquals(500_000_000_000L, MoneroUri.parseXmrToAtomic(".5"));
	}

	@Test
	public void malformedAmountsAreRejected() {
		expectInvalid("1.2.3");
		expectInvalid("-1");
		expectInvalid("abc");
		expectInvalid("0.0000000000001");
		expectInvalid("");
		expectInvalid("99999999999999999999");
	}

	@Test
	public void emptyInputIsNull() throws Exception {
		assertNull(MoneroUri.parse(""));
		assertNull(MoneroUri.parse("   "));
		assertNull(MoneroUri.parse("monero:"));
	}

	private static void expectInvalid(String amount) {
		try {
			MoneroUri.parseXmrToAtomic(amount);
			fail("expected rejection of " + amount);
		} catch (XmrError.XmrException e) {
			assertEquals(XmrError.SEND_SNAPSHOT_INVALID, e.error);
		}
	}
}
