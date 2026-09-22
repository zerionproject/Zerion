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
		assertEquals(BtcWallet.MAX_FEE_RATE, BtcWallet.rateFor(e, 1), 1e-9);
		assertTrue("server ceiling is a plausible rate",
				BtcWallet.MAX_FEE_RATE <= 200.0);
	}

	/** BTC-04: a NaN estimate passed every comparison and yielded a zero fee. */
	@Test
	public void nanEstimateTakesTheFloor() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.feeBtcPerKb = Double.NaN;
		assertEquals(BtcWallet.MIN_FEE_RATE, BtcWallet.rateFor(e, 6), 1e-9);
		e.feeBtcPerKb = Double.NEGATIVE_INFINITY;
		assertEquals(BtcWallet.MIN_FEE_RATE, BtcWallet.rateFor(e, 6), 1e-9);
		e.feeBtcPerKb = Double.POSITIVE_INFINITY;
		assertEquals(BtcWallet.MAX_FEE_RATE, BtcWallet.rateFor(e, 6), 1e-9);
	}

	@Test
	public void nanRateGivenToAPlanCannotProduceAZeroFee()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "w",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		BtcWallet.SendPlan p = w.planSend(DEST, 40000, Double.NaN, false,
				null, false);
		assertTrue("fee floored by size", p.feeSat
				>= BtcWallet.minimumFeeSat(p.vbytes));
		assertTrue(p.feeSat > 0);
		BtcWallet.SendPlan sweep = w.planSend(DEST, 0, Double.NaN, true,
				null, false);
		assertTrue(sweep.feeSat >= BtcWallet.minimumFeeSat(sweep.vbytes));
	}

	/** BTC-10: the plan reports its effective rate and the fee's share. */
	@Test
	public void planReportsEffectiveRateAndShareOfAmount()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "w",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		BtcWallet.SendPlan p = w.planSend(DEST, 10000, 100.0, false, null,
				false);
		assertEquals(100.0, p.feeRateSatPerVb(), 1.0);
		assertTrue("a 100 sat/vB fee on 10000 sat is flagged as high",
				p.feePercentOfAmount() >= BtcWallet.HIGH_FEE_PERCENT);
		BtcWallet.SendPlan cheap = w.planSend(DEST, 10000, 2.0, false, null,
				false);
		assertTrue(cheap.feePercentOfAmount() < BtcWallet.HIGH_FEE_PERCENT);
		BtcWallet.SendPlan capped = w.planSend(DEST, 10000, 5000.0, false,
				null, false);
		assertTrue("caller rate is capped too",
				capped.feeRateSatPerVb() <= BtcWallet.MAX_FEE_RATE + 1.0);
	}

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	@Test
	public void normalEstimatePassesThrough() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.feeBtcPerKb = 0.0001;
		assertEquals(10.0, BtcWallet.rateFor(e, 3), 1e-9);
	}
}
