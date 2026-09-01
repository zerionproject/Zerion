package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public class SpCursorTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String ORACLE = "https://oracle.example.org";

	private static BtcWallet wallet(SilentPaymentScanner.Fetcher fetcher) {
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "walletA",
				new FakeElectrum.RecordingFactory(new FakeElectrum()), fetcher);
		w.setSilentPaymentsEnabled(true);
		return w;
	}

	@Test
	public void cursorReachesTipWhenEveryBlockScans() throws IOException {
		SilentPaymentScanner.Fetcher ok = (url, tag) -> {
			if (url.contains("block-height")) {
				return "{\"block_height\":800005}";
			}
			return "[]";
		};
		BtcWallet.SpScanResult r =
				wallet(ok).scanSilentPayments(ORACLE, 800000, 100);
		assertEquals(800005, r.scannedTo);
		assertTrue(r.found.isEmpty());
	}

	@Test
	public void midScanFailureFailsClosedAndDoesNotAdvance() {
		SilentPaymentScanner.Fetcher flaky = (url, tag) -> {
			if (url.contains("block-height")) {
				return "{\"block_height\":800005}";
			}
			if (url.contains("/tweaks/800003")) {
				return null;
			}
			return "[]";
		};
		assertThrows(IOException.class, () ->
				wallet(flaky).scanSilentPayments(ORACLE, 800000, 100));
	}

	@Test
	public void unreachableOracleThrows() {
		SilentPaymentScanner.Fetcher down = (url, tag) -> null;
		assertThrows(IOException.class, () ->
				wallet(down).scanSilentPayments(ORACLE, 800000, 100));
	}
}
