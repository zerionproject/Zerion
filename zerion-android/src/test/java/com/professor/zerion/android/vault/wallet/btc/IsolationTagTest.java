package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

public class IsolationTagTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String ORACLE = "https://oracle.example.org";

	@Test
	public void electrumUsesWalletIdAsIsolationTag() throws IOException {
		FakeElectrum.RecordingFactory factory =
				new FakeElectrum.RecordingFactory(new FakeElectrum());
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001,
				"wallet-A", factory, (url, tag) -> null);
		w.feeOptions();
		assertEquals("wallet-A", factory.lastIsolationTag);
	}

	@Test
	public void twoWalletsGetDistinctElectrumTags() throws IOException {
		FakeElectrum.RecordingFactory fa =
				new FakeElectrum.RecordingFactory(new FakeElectrum());
		FakeElectrum.RecordingFactory fb =
				new FakeElectrum.RecordingFactory(new FakeElectrum());
		new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "wallet-A", fa,
				(url, tag) -> null).feeOptions();
		new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "wallet-B", fb,
				(url, tag) -> null).feeOptions();
		assertEquals("wallet-A", fa.lastIsolationTag);
		assertEquals("wallet-B", fb.lastIsolationTag);
	}

	@Test
	public void silentPaymentScanUsesDedicatedContextDistinctFromScan()
			throws IOException {
		AtomicReference<String> seenTag = new AtomicReference<>(null);
		SilentPaymentScanner.Fetcher recording = (url, tag) -> {
			seenTag.set(tag);
			if (url.contains("block-height")) {
				return "{\"block_height\":800000}";
			}
			return "[]";
		};
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001,
				"wallet-A", new FakeElectrum.RecordingFactory(new FakeElectrum()),
				recording);
		w.setSilentPaymentsEnabled(true);
		w.scanSilentPayments(ORACLE, 800000, 1);
		assertEquals(TorIsolation.silentPayment("wallet-A"), seenTag.get());
		org.junit.Assert.assertNotEquals(TorIsolation.scan("wallet-A"),
				seenTag.get());
	}
}
