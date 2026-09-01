package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public class PollingReductionTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";
	private static final String TX1 =
			"3333333333333333333333333333333333333333333333333333333333333333";

	private static BtcWallet wallet(FakeElectrum e) {
		return new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
	}

	@Test
	public void lightScanIssuesFewerQueriesThanFull() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 10000);
		BtcWallet w = wallet(e);
		w.scan();
		int full = e.getHistoryCalls;
		e.resetCounters();
		w.scanLight();
		int light = e.getHistoryCalls;
		assertTrue("light=" + light + " full=" + full, light < full);
	}

	@Test
	public void lightScanFindsFundsOnActiveAndLookaheadAddresses()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 10000);
		BtcWallet w = wallet(e);
		w.scan();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 2), TX1, 0, 5000);
		BtcWallet.ScanResult r = w.scanLight();
		assertEquals(15000L, r.balanceSat);
	}

	@Test
	public void lightScanMissesBeyondLookaheadButFullRefreshFindsIt()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 10000);
		BtcWallet w = wallet(e);
		w.scan();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 10), TX1, 0, 7000);
		assertEquals(10000L, w.scanLight().balanceSat);
		assertEquals(17000L, w.scan().balanceSat);
	}

	@Test
	public void lightScanFallsBackToFullWhenNeverScanned() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 5), TX0, 0, 8000);
		BtcWallet w = wallet(e);
		assertEquals(8000L, w.scanLight().balanceSat);
	}
}
