package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TaprootSignTest {

	@Test
	public void officialBip340And341VectorsPass() {
		assertTrue(TaprootSign.selfTest());
	}
}
