package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.IOException;

public class ElectrumTransportFailClosedTest {

	private static ElectrumEndpoint onion() {
		return new ElectrumEndpoint(
				"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaad.onion",
				50001, ElectrumEndpoint.Mode.ONION, false, null);
	}

	@Test
	public void onionEndpointRequiresTorNoFallback() {
		assertThrows(IOException.class,
				() -> new ElectrumClient(onion(), 0, "w"));
		assertThrows(IOException.class,
				() -> new ElectrumClient(onion(), -1, "w"));
	}

	@Test
	public void tlsEndpointRequiresTorNoFallback() {
		ElectrumEndpoint tls = new ElectrumEndpoint("electrum.example.org",
				50002, ElectrumEndpoint.Mode.TLS, false, null);
		assertThrows(IOException.class, () -> new ElectrumClient(tls, 0, "w"));
	}

	@Test
	public void plaintextEndpointRequiresTorNoFallback() {
		ElectrumEndpoint plain = new ElectrumEndpoint("electrum.example.org",
				50001, ElectrumEndpoint.Mode.PLAINTEXT, false, null);
		assertThrows(IOException.class, () -> new ElectrumClient(plain, 0, "w"));
	}
}
