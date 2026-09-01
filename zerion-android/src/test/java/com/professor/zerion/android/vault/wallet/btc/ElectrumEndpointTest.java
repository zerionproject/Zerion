package com.professor.zerion.android.vault.wallet.btc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ElectrumEndpointTest {

	@Test
	public void legacyHostPortInfersPlaintext() {
		ElectrumEndpoint e = ElectrumEndpoint.parse("electrum.example.org:50001");
		assertEquals(ElectrumEndpoint.Mode.PLAINTEXT, e.mode);
		assertTrue(e.viaTor());
		assertFalse(e.tls());
	}

	@Test
	public void port50002InfersTls() {
		ElectrumEndpoint e = ElectrumEndpoint.parse("electrum.example.org:50002");
		assertEquals(ElectrumEndpoint.Mode.TLS, e.mode);
		assertTrue(e.tls());
		assertTrue(e.viaTor());
	}

	@Test
	public void onionHostIsOnionAndViaTor() {
		ElectrumEndpoint e = ElectrumEndpoint.parse(
				"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaad.onion:50001");
		assertTrue(e.isOnion());
		assertTrue(e.viaTor());
		assertFalse(e.tls());
	}

	@Test
	public void encodeParseRoundTripTlsPinnedLocal() {
		String pin = "ab".repeat(32);
		ElectrumEndpoint e = new ElectrumEndpoint("192.168.1.9", 50002,
				ElectrumEndpoint.Mode.TLS, true, pin);
		ElectrumEndpoint back = ElectrumEndpoint.parse(e.encode());
		assertEquals("192.168.1.9", back.host);
		assertEquals(50002, back.port);
		assertEquals(ElectrumEndpoint.Mode.TLS, back.mode);
		assertTrue(back.local);
		assertFalse(back.viaTor());
		assertTrue(back.pinned());
		assertEquals(pin, back.pinSha256);
	}

	@Test
	public void onionCannotBeLocal() {
		assertThrows(IllegalArgumentException.class, () -> new ElectrumEndpoint(
				"x.onion", 50001, ElectrumEndpoint.Mode.ONION, true, null));
	}

	@Test
	public void onionModeRequiresOnionHost() {
		assertThrows(IllegalArgumentException.class, () -> new ElectrumEndpoint(
				"example.org", 50001, ElectrumEndpoint.Mode.ONION, false, null));
	}

	@Test
	public void pinOnlyOnTls() {
		assertThrows(IllegalArgumentException.class, () -> new ElectrumEndpoint(
				"example.org", 50001, ElectrumEndpoint.Mode.PLAINTEXT, false,
				"abcd"));
	}

	@Test
	public void lanHostsDetected() {
		assertTrue(ElectrumEndpoint.isLanHost("192.168.1.5"));
		assertTrue(ElectrumEndpoint.isLanHost("10.0.0.9"));
		assertTrue(ElectrumEndpoint.isLanHost("172.16.0.1"));
		assertTrue(ElectrumEndpoint.isLanHost("172.31.255.1"));
		assertTrue(ElectrumEndpoint.isLanHost("mynode.local"));
		assertTrue(ElectrumEndpoint.isLanHost("localhost"));
		assertFalse(ElectrumEndpoint.isLanHost("172.32.0.1"));
		assertFalse(ElectrumEndpoint.isLanHost("8.8.8.8"));
		assertFalse(ElectrumEndpoint.isLanHost("electrum.example.org"));
	}

	@Test
	public void preferredDefaultPrefersOnionWhenSet() {
		assertEquals("abc.onion:50001",
				ElectrumEndpoint.preferredDefaultSpec("abc.onion:50001",
						"electrum.example.org:50002"));
		assertEquals("electrum.example.org:50002",
				ElectrumEndpoint.preferredDefaultSpec("",
						"electrum.example.org:50002"));
		assertEquals("electrum.example.org:50002",
				ElectrumEndpoint.preferredDefaultSpec(null,
						"electrum.example.org:50002"));
	}

	@Test
	public void pinAppliedOnlyToTlsEndpoints() {
		String pin = "ab".repeat(32);
		assertTrue(ElectrumEndpoint.fromUserInput("host.example.org", 50002, pin)
				.pinned());
		assertFalse(ElectrumEndpoint.fromUserInput("host.example.org", 50001,
				pin).pinned());
		assertFalse(ElectrumEndpoint.fromUserInput("abc.onion", 50001, pin)
				.pinned());
		ElectrumEndpoint lanPinned = ElectrumEndpoint.fromUserInput(
				"192.168.1.9", 50002, pin);
		assertTrue(lanPinned.pinned());
		assertTrue(lanPinned.local);
	}

	@Test
	public void fromUserInputClassifiesOwnNodeModes() {
		ElectrumEndpoint onion = ElectrumEndpoint.fromUserInput("abc.onion",
				50001, null);
		assertTrue(onion.isOnion());
		assertTrue(onion.viaTor());

		ElectrumEndpoint lan = ElectrumEndpoint.fromUserInput("192.168.1.9",
				50002, null);
		assertEquals(ElectrumEndpoint.Mode.TLS, lan.mode);
		assertTrue(lan.local);
		assertFalse(lan.viaTor());

		ElectrumEndpoint remote = ElectrumEndpoint.fromUserInput(
				"electrum.example.org", 50002, null);
		assertEquals(ElectrumEndpoint.Mode.TLS, remote.mode);
		assertFalse(remote.local);
		assertTrue(remote.viaTor());
	}
}
