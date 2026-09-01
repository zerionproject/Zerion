package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;

public class BroadcastSeparationTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	@Test
	public void broadcastUsesDistinctEndpointAndCircuit() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		FakeElectrum.RecordingFactory f = new FakeElectrum.RecordingFactory(e);
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999,
				ElectrumEndpoint.parse("scan.example.org:50002"),
				ElectrumEndpoint.parse("bcast.example.org:50002"), "walletA", f,
				(url, tag) -> null);

		w.send(DEST, 50000, 1.0, false);

		boolean scanSeen = false;
		boolean broadcastSeen = false;
		for (int i = 0; i < f.endpoints.size(); i++) {
			String host = f.endpoints.get(i).host;
			String tag = f.tags.get(i);
			if (host.equals("scan.example.org")) {
				scanSeen = true;
				assertEquals("walletA", tag);
			}
			if (host.equals("bcast.example.org")) {
				broadcastSeen = true;
				assertEquals("walletA-b", tag);
			}
		}
		assertTrue(scanSeen);
		assertTrue(broadcastSeen);
	}

	@Test
	public void singleEndpointStillBroadcastsOnSeparateCircuit()
			throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		FakeElectrum.RecordingFactory f = new FakeElectrum.RecordingFactory(e);
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, "one.example.org", 50002,
				"walletA", f, (url, tag) -> null);

		w.send(DEST, 50000, 1.0, false);

		assertTrue(f.tags.contains("walletA"));
		assertTrue(f.tags.contains("walletA-b"));
	}

	@Test
	public void broadcastFailureStillThrowsAfterDecoupling() {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		e.broadcastError = new IOException("connection closed");
		FakeElectrum.RecordingFactory f = new FakeElectrum.RecordingFactory(e);
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999,
				ElectrumEndpoint.parse("scan.example.org:50002"),
				ElectrumEndpoint.parse("bcast.example.org:50002"), "walletA", f,
				(url, tag) -> null);
		assertThrows(IOException.class, () -> w.send(DEST, 50000, 1.0, false));
	}
}
