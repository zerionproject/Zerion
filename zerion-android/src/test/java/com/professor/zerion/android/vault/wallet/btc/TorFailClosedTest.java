package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;

public class TorFailClosedTest {

	@Test
	public void electrumRefusesWhenTorNotReady() {
		assertThrows(IOException.class, () ->
				new ElectrumClient("electrum.example.org", 50001, 0, "wallet"));
		assertThrows(IOException.class, () ->
				new ElectrumClient("electrum.example.org", 50001, -1, "wallet"));
	}

	@Test
	public void torHttpReturnsNullWhenTorNotReady() {
		assertNull(TorHttp.get("https://oracle.example.org/x", 0, "wallet"));
		assertNull(TorHttp.get("https://oracle.example.org/x", -1, "wallet"));
	}

	@Test
	public void scanBlockFailsClosedWhenFetchFails() {
		SilentPaymentScanner.Fetcher down = (url, tag) -> null;
		assertThrows(IOException.class, () -> SilentPaymentScanner.scanBlock(
				"https://oracle.example.org", 800000, new byte[32],
				new byte[33], "wallet", down));
	}

	@Test
	public void tipHeightNullWhenFetchFails() {
		SilentPaymentScanner.Fetcher down = (url, tag) -> null;
		assertNull(SilentPaymentScanner.tipHeight("https://oracle.example.org",
				"wallet", down));
	}
}
