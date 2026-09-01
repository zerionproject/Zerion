package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class BtcKeysTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";

	@Test
	public void derivesBip84TestVectorAddresses() {
		assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
				BtcKeys.address(MNEMONIC, 0, 0));
		assertEquals("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g",
				BtcKeys.address(MNEMONIC, 0, 1));
		assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el",
				BtcKeys.changeAddress(MNEMONIC, 0, 0));
	}

	@Test
	public void validatesAddresses() {
		assertTrue(BtcKeys.isValidAddress(
				"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"));
		assertFalse(BtcKeys.isValidAddress("definitely not a bitcoin address"));
	}
}
