package com.professor.zerion.android.vault.wallet.btc;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BtcKeysTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";

	@Test
	public void derivesBip84TestVectorAddresses() throws Exception {
		BtcKeys.Account a = BtcKeys.Account.fromMnemonic(
				MNEMONIC.toCharArray(), 0);
		assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
				a.address(0));
		assertEquals("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g",
				a.address(1));
		assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el",
				a.changeAddress(0));
	}

	@Test
	public void validatesAddresses() {
		assertTrue(BtcKeys.isValidAddress(
				"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"));
		assertFalse(BtcKeys.isValidAddress("definitely not a bitcoin address"));
	}
}
