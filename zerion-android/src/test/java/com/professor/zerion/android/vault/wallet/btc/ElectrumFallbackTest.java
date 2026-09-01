package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class ElectrumFallbackTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";
	private static final String DEST =
			"bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu";
	private static final String TX0 =
			"2222222222222222222222222222222222222222222222222222222222222222";

	static final class SelectiveFactory implements ElectrumRpc.Factory {
		final FakeElectrum backend;
		final String failHost;
		final List<String> opened = new ArrayList<>();

		SelectiveFactory(FakeElectrum backend, String failHost) {
			this.backend = backend;
			this.failHost = failHost;
		}

		@Override
		public ElectrumRpc open(ElectrumEndpoint ep, int socksPort, String tag)
				throws IOException {
			opened.add(ep.host);
			if (ep.host.equals(failHost)) {
				throw new IOException("primary unreachable");
			}
			return backend;
		}
	}

	private static ElectrumEndpoint onion() {
		return new ElectrumEndpoint("egserver.onion", 50001,
				ElectrumEndpoint.Mode.ONION, false, null);
	}

	private static ElectrumEndpoint tls() {
		return ElectrumEndpoint.parse("electrum.example.org:50002");
	}

	@Test
	public void scanFallsBackToTlsWhenOnionUnavailable() throws IOException {
		FakeElectrum e = new FakeElectrum();
		SelectiveFactory f = new SelectiveFactory(e, "egserver.onion");
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, onion(), onion(),
				"walletA", f, (url, tag) -> null);
		w.setFallback(tls(), tls());
		BtcWallet.ScanResult r = w.scan();
		assertEquals(0L, r.balanceSat);
		assertTrue(f.opened.contains("egserver.onion"));
		assertTrue(f.opened.contains("electrum.example.org"));
	}

	@Test
	public void noFallbackConfiguredStillFailsClosed() {
		FakeElectrum e = new FakeElectrum();
		SelectiveFactory f = new SelectiveFactory(e, "egserver.onion");
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, onion(), onion(),
				"walletA", f, (url, tag) -> null);
		assertThrows(IOException.class, w::scan);
	}

	@Test
	public void broadcastFallsBackToTls() throws IOException {
		FakeElectrum e = new FakeElectrum();
		e.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), TX0, 0, 100000);
		SelectiveFactory f = new SelectiveFactory(e, "egserver.onion");
		BtcWallet w = new BtcWallet(MNEMONIC, 0, 9999, onion(), onion(),
				"walletA", f, (url, tag) -> null);
		w.setFallback(tls(), tls());
		String txid = w.send(DEST, 50000, 1.0, false);
		assertEquals(e.lastBroadcastTxid(), txid);
		assertTrue(f.opened.contains("electrum.example.org"));
	}
}
