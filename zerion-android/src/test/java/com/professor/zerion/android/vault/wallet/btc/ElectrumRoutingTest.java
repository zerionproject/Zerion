package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ElectrumRoutingTest {

	@Test
	public void torEndpointRoutesOverTor() {
		ElectrumEndpoint onion = ElectrumEndpoint.parse(
				"kittycp2gatrqhlwpmbczk5rblw62enrpo2rzwtkfrrr27hq435d4vid.onion"
						+ ":50001");
		assertTrue("onion default must use Tor", onion.viaTor());
		assertFalse(onion.direct);
	}

	@Test
	public void directTlsEndpointDoesNotUseTorAndKeepsTls() {
		ElectrumEndpoint tls = new ElectrumEndpoint(
				"electrum.example", 50002, ElectrumEndpoint.Mode.TLS, false, null);
		ElectrumEndpoint direct = tls.asDirect();
		assertTrue("direct must be marked direct", direct.direct);
		assertFalse("direct must not tunnel over Tor", direct.viaTor());
		assertTrue("direct must remain verified TLS", direct.tls());
	}

	@Test
	public void directRoutingRejectsPlaintext() {
		try {
			new ElectrumEndpoint("node.example", 50001,
					ElectrumEndpoint.Mode.PLAINTEXT, false, true, null);
			fail("plaintext direct must be rejected");
		} catch (IllegalArgumentException expected) {
			// expected: direct requires verified TLS
		}
	}

	@Test
	public void asDirectRejectsOnion() {
		ElectrumEndpoint onion = ElectrumEndpoint.parse(
				"kittycp2gatrqhlwpmbczk5rblw62enrpo2rzwtkfrrr27hq435d4vid.onion"
						+ ":50001");
		try {
			onion.asDirect();
			fail("onion cannot become a direct clearnet endpoint");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}

	@Test
	public void directAndLocalAreMutuallyExclusive() {
		try {
			new ElectrumEndpoint("192.168.1.2", 50002,
					ElectrumEndpoint.Mode.TLS, true, true, null);
			fail("an endpoint cannot be both direct and local");
		} catch (IllegalArgumentException expected) {
			// expected
		}
	}
}
