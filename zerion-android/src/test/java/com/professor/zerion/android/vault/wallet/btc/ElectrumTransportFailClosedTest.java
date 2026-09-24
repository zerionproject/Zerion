package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
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
	public void plaintextToANonLocalHostCannotExist() {
		assertThrows(IllegalArgumentException.class,
				() -> new ElectrumEndpoint("electrum.example.org", 50001,
						ElectrumEndpoint.Mode.PLAINTEXT, false, null));
		ElectrumEndpoint clearnet = ElectrumEndpoint.fromUserInput(
				"electrum.example.org", 50001, null);
		assertEquals(ElectrumEndpoint.Mode.TLS, clearnet.mode);
		assertThrows(IOException.class,
				() -> new ElectrumClient(clearnet, 0, "w"));
	}
}
