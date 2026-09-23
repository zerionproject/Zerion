package com.professor.zerion.android.vault.wallet.btc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/** A2-REG-BTC-16: the scanned-request parser honours BIP21's req- rule. */
public class Bip21RequestTest {

	private static final String ADDR = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";

	@Test
	public void aPlainAddressPassesThrough() {
		Bip21Request r = Bip21Request.parse("  " + ADDR + " ");
		assertNotNull(r);
		assertEquals(ADDR, r.address);
		assertNull(r.amount);
	}

	@Test
	public void aUriYieldsAddressAndAmount() {
		Bip21Request r = Bip21Request.parse("BitCoin:" + ADDR
				+ "?label=x&amount=0.01&message=hi%20there");
		assertNotNull(r);
		assertEquals(ADDR, r.address);
		assertEquals("0.01", r.amount);
	}

	@Test
	public void anUnknownRequiredParameterRefusesTheRequest() {
		assertNull(Bip21Request.parse("bitcoin:" + ADDR
				+ "?req-expires=1&amount=0.01"));
		assertNull(Bip21Request.parse("bitcoin:" + ADDR + "?REQ-somefeature"));
		assertNull(Bip21Request.parse("bitcoin:" + ADDR
				+ "?amount=0.01&req-pj=https%3A%2F%2Fexample.invalid"));
	}

	@Test
	public void anOptionalUnknownParameterIsIgnored() {
		Bip21Request r = Bip21Request.parse("bitcoin:" + ADDR
				+ "?somefeature=1&amount=1");
		assertNotNull(r);
		assertEquals("1", r.amount);
	}
}
