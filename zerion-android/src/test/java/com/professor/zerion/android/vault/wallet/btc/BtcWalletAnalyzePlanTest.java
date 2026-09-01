package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.professor.zerion.android.vault.wallet.btc.privacy.PrivacyAnalyzer;

import org.junit.Test;

import java.io.IOException;

public class BtcWalletAnalyzePlanTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";
	private static final String TX1 =
			"3333333333333333333333333333333333333333333333333333333333333333";

	private static BtcWallet wallet(FakeElectrum e) {
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		w.setPrivacyStore(new BtcWalletPrivacyTest.MemStore());
		return w;
	}

	private static boolean has(PrivacyAnalyzer.Analysis a, String code) {
		for (PrivacyAnalyzer.Finding f : a.findings) {
			if (f.code.equals(code)) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void singleClusterPlanIsHigh() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = wallet(e);
		PrivacyAnalyzer.Analysis a =
				w.analyzePlan(w.planSend(DEST, 40000, 1.0, false, null, false));
		assertEquals(PrivacyAnalyzer.Level.HIGH, a.level);
		assertTrue(has(a, PrivacyAnalyzer.SINGLE_CLUSTER));
		assertTrue(has(a, PrivacyAnalyzer.CHANGE_ISOLATED));
	}

	@Test
	public void multiClusterPlanIsLow() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 30000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 1), TX1, 0, 30000);
		BtcWallet w = wallet(e);
		PrivacyAnalyzer.Analysis a =
				w.analyzePlan(w.planSend(DEST, 50000, 1.0, false, null, false));
		assertEquals(PrivacyAnalyzer.Level.LOW, a.level);
		assertTrue(has(a, PrivacyAnalyzer.MERGE_CLUSTERS));
	}

	@Test
	public void addressReuseDetectedFromLocalState() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 50000);
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX1, 1, 50000);
		BtcWallet w = wallet(e);
		PrivacyAnalyzer.Analysis a =
				w.analyzePlan(w.planSend(DEST, 40000, 1.0, false, null, false));
		assertTrue(has(a, PrivacyAnalyzer.ADDRESS_REUSE));
		assertEquals(PrivacyAnalyzer.Level.MEDIUM, a.level);
	}

	@Test
	public void analysisWorksWithoutPrivacyMetadata() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(e), (url, tag) -> null);
		PrivacyAnalyzer.Analysis a =
				w.analyzePlan(w.planSend(DEST, 40000, 1.0, false, null, false));
		assertEquals(PrivacyAnalyzer.Level.HIGH, a.level);
	}
}
