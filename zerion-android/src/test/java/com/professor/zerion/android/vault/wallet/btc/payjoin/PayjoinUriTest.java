package com.professor.zerion.android.vault.wallet.btc.payjoin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PayjoinUriTest {

	private static final String ADDR = "bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4";

	@Test
	public void validPayjoinUriDetected() {
		PayjoinUri u = PayjoinUri.detect("bitcoin:" + ADDR
				+ "?amount=0.001&pj=https://payjo.in/ABC123&pjos=0");
		assertEquals(PayjoinUri.Kind.PAYJOIN, u.kind);
		assertTrue(u.isPayjoin());
		assertEquals(ADDR, u.address);
		assertEquals(100000, u.amountSat);
		assertEquals("https://payjo.in/ABC123", u.pjEndpoint);
	}

	@Test
	public void normalBip21RemainsNormal() {
		PayjoinUri u = PayjoinUri.detect("bitcoin:" + ADDR + "?amount=0.5");
		assertEquals(PayjoinUri.Kind.NORMAL, u.kind);
		assertFalse(u.isPayjoin());
		assertEquals(ADDR, u.address);
		assertEquals(50000000, u.amountSat);
	}

	@Test
	public void plainAddressIsNormal() {
		PayjoinUri u = PayjoinUri.detect("bitcoin:" + ADDR);
		assertEquals(PayjoinUri.Kind.NORMAL, u.kind);
	}

	@Test
	public void nonBitcoinSchemeIsNormal() {
		assertEquals(PayjoinUri.Kind.NORMAL,
				PayjoinUri.detect("https://example.com").kind);
	}

	@Test
	public void malformedPjEndpointRejected() {
		assertEquals(PayjoinUri.Kind.MALFORMED,
				PayjoinUri.detect("bitcoin:" + ADDR + "?pj=notaurl").kind);
	}

	@Test
	public void emptyPjRejected() {
		assertEquals(PayjoinUri.Kind.MALFORMED,
				PayjoinUri.detect("bitcoin:" + ADDR + "?pj=").kind);
	}

	@Test
	public void pjWithoutHostRejected() {
		assertEquals(PayjoinUri.Kind.MALFORMED,
				PayjoinUri.detect("bitcoin:" + ADDR + "?pj=https://").kind);
	}

	@Test
	public void pjWithoutAddressRejected() {
		assertEquals(PayjoinUri.Kind.MALFORMED,
				PayjoinUri.detect("bitcoin:?pj=https://payjo.in/X").kind);
	}

	@Test
	public void outputSubstitutionFlagParsed() {
		PayjoinUri u = PayjoinUri.detect("bitcoin:" + ADDR
				+ "?pj=https://payjo.in/X&pjos=1");
		assertTrue(u.outputSubstitution);
	}
}
