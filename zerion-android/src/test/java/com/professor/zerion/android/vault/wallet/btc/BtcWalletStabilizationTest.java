package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class BtcWalletStabilizationTest {

	private static final String MNEMONIC =
			"abandon abandon abandon abandon abandon abandon abandon abandon "
					+ "abandon abandon abandon about";

	private static BtcWallet wallet(ElectrumRpc.Factory f) {
		return new BtcWallet(MNEMONIC, 0, 9999, "primary.onion", 50001,
				"walletA", f, (url, tag) -> null);
	}

	@Test
	public void failoverTriesEndpointsInOrderUntilOneSucceeds()
			throws IOException {
		FakeElectrum backend = new FakeElectrum();
		List<String> attempts = new ArrayList<>();
		ElectrumRpc.Factory f = (endpoint, socksPort, tag) -> {
			attempts.add(endpoint.host);
			if (endpoint.host.equals("primary.onion")
					|| endpoint.host.equals("second.onion")) {
				throw new IOException("node down");
			}
			return backend;
		};
		BtcWallet w = wallet(f);
		w.setFallbacks(
				Arrays.asList(ElectrumEndpoint.parse("second.onion:50001"),
						ElectrumEndpoint.parse("tls.example:50002")),
				Arrays.asList(ElectrumEndpoint.parse("second.onion:50001"),
						ElectrumEndpoint.parse("tls.example:50002")));

		BtcWallet.ScanResult r = w.scan();

		assertNotNull(r);
		assertEquals("primary tried, then second, then tls fallback",
				Arrays.asList("primary.onion", "second.onion", "tls.example"),
				attempts);
	}

	@Test
	public void failoverThrowsWhenAllEndpointsDown() {
		ElectrumRpc.Factory f = (endpoint, socksPort, tag) -> {
			throw new IOException("all down");
		};
		BtcWallet w = wallet(f);
		w.setFallbacks(
				Arrays.asList(ElectrumEndpoint.parse("tls.example:50002")),
				Arrays.asList(ElectrumEndpoint.parse("tls.example:50002")));
		boolean threw = false;
		try {
			w.scan();
		} catch (IOException e) {
			threw = true;
		}
		assertTrue("scan must fail closed when every endpoint is down", threw);
	}

	@Test
	public void singleServerScanUsesExactlyOneConnection() throws IOException {
		FakeElectrum backend = new FakeElectrum();
		int[] opens = {0};
		ElectrumRpc.Factory f = (endpoint, socksPort, tag) -> {
			opens[0]++;
			return backend;
		};
		wallet(f).scan();
		assertEquals("no query-spreading: one scan opens one connection",
				1, opens[0]);
	}

	@Test
	public void cachedScanIsReusedForPlanningWithoutNetwork()
			throws IOException {
		FakeElectrum backend = new FakeElectrum();
		String txid =
				"2222222222222222222222222222222222222222222222222222222222222222";
		backend.addUtxo(BtcKeys.scriptHash(MNEMONIC, 0, 0), txid, 0, 100000);
		int[] opens = {0};
		ElectrumRpc.Factory f = (endpoint, socksPort, tag) -> {
			opens[0]++;
			return backend;
		};
		BtcWallet w = wallet(f);
		BtcWallet.ScanResult scan = w.scan();
		assertNotNull(w.cachedScan());
		int opensAfterScan = opens[0];

		BtcWallet.SendPlan plan = w.planSend(scan,
				BtcKeys.address(MNEMONIC, 0, 0), 10000, 5.0, false, null, true);

		assertNotNull(plan);
		assertEquals("planning from a cached scan opens no new connection",
				opensAfterScan, opens[0]);
	}

	@Test
	public void invalidateCachedScanClearsIt() throws IOException {
		FakeElectrum backend = new FakeElectrum();
		BtcWallet w = wallet((endpoint, socksPort, tag) -> backend);
		w.scan();
		assertNotNull(w.cachedScan());
		w.invalidateCachedScan();
		assertNull(w.cachedScan());
	}

	@Test
	public void scanResultCarriesFreshReceiveIndex() throws IOException {
		FakeElectrum backend = new FakeElectrum();
		String txid =
				"3333333333333333333333333333333333333333333333333333333333333333";
		backend.addHistoryOnly(BtcKeys.scriptHash(MNEMONIC, 0, 0), txid);
		backend.addHistoryOnly(BtcKeys.scriptHash(MNEMONIC, 0, 1), txid);
		BtcWallet.ScanResult r =
				wallet((endpoint, socksPort, tag) -> backend).scan();
		assertEquals("fresh receive index skips used addresses", 2,
				r.receiveIndex);
		assertEquals(BtcKeys.address(MNEMONIC, 0, 2), r.receiveAddress);
	}
}
