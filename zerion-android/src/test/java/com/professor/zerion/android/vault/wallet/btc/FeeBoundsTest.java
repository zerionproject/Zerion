package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public class FeeBoundsTest {

	@Test
	public void zeroEstimateIsFlooredNeverZero() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.feeBtcPerKb = 0.0;
		assertEquals(2.0, BtcWallet.rateFor(e, 6), 1e-9);
	}

	@Test
	public void tinyEstimateIsFloored() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.feeBtcPerKb = 0.00000001;
		assertTrue(BtcWallet.rateFor(e, 6) >= 2.0);
	}

	@Test
	public void hugeEstimateIsClamped() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.feeBtcPerKb = 1.0;
		assertEquals(1000.0, BtcWallet.rateFor(e, 1), 1e-9);
	}

	@Test
	public void normalEstimatePassesThrough() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.feeBtcPerKb = 0.0001;
		assertEquals(10.0, BtcWallet.rateFor(e, 3), 1e-9);
	}
}
