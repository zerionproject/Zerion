package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

public class SilentPaymentsToggleTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String ORACLE = "https://oracle.example.org";

	private static BtcWallet wallet(AtomicInteger spCalls) {
		SilentPaymentScanner.Fetcher counting = (url, tag) -> {
			spCalls.incrementAndGet();
			if (url.contains("block-height")) {
				return "{\"block_height\":800000}";
			}
			return "[]";
		};
		return new BtcWallet(MNEMONIC, 0, 9999, "host", 50001, "wallet-A",
				new FakeElectrum.RecordingFactory(new FakeElectrum()), counting);
	}

	@Test
	public void defaultDisabledDoesZeroSpNetwork() throws IOException {
		AtomicInteger spCalls = new AtomicInteger(0);
		BtcWallet w = wallet(spCalls);
		assertFalse(w.isSilentPaymentsEnabled());
		BtcWallet.SpScanResult r = w.scanSilentPayments(ORACLE, 799990, 20);
		assertEquals(0, spCalls.get());
		assertTrue(r.found.isEmpty());
	}

	@Test
	public void enablingStartsScanningOverNetwork() throws IOException {
		AtomicInteger spCalls = new AtomicInteger(0);
		BtcWallet w = wallet(spCalls);
		w.setSilentPaymentsEnabled(true);
		assertTrue(w.isSilentPaymentsEnabled());
		w.scanSilentPayments(ORACLE, 800000, 1);
		assertTrue(spCalls.get() > 0);
	}

	@Test
	public void disablingAgainStopsFutureScanning() throws IOException {
		AtomicInteger spCalls = new AtomicInteger(0);
		BtcWallet w = wallet(spCalls);
		w.setSilentPaymentsEnabled(true);
		w.scanSilentPayments(ORACLE, 800000, 1);
		int afterEnabled = spCalls.get();
		assertTrue(afterEnabled > 0);
		w.setSilentPaymentsEnabled(false);
		w.scanSilentPayments(ORACLE, 800000, 1);
		assertEquals(afterEnabled, spCalls.get());
	}

	@Test
	public void toggleReflectsState() {
		AtomicInteger spCalls = new AtomicInteger(0);
		BtcWallet w = wallet(spCalls);
		assertFalse(w.isSilentPaymentsEnabled());
		w.setSilentPaymentsEnabled(true);
		assertTrue(w.isSilentPaymentsEnabled());
		w.setSilentPaymentsEnabled(false);
		assertFalse(w.isSilentPaymentsEnabled());
	}
}
