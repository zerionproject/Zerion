package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SilentPaymentTest {

	@Test
	public void officialBip352VectorPasses() {
		assertTrue(SilentPayment.selfTest());
	}
}
