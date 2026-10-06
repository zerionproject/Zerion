package org.zerionproject.transport;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TorBridgeConfiguratorTest {

	@Test
	public void everyLineBecomesExactlyOneBridgeLine() {
		List<String> bridges = TorBridgeConfigurator.parseCustomBridges(
				"obfs4 192.0.2.1:443 AAAA cert=BBBB iat-mode=0\r\n"
						+ "\n"
						+ "   \n"
						+ "Bridge obfs4 192.0.2.2:443 CCCC\n"
						+ "bridge obfs4 192.0.2.3:443 DDDD\n"
						+ "  snowflake 192.0.2.4:80 EEEE  \n"
						+ "DisableNetwork 0\n"
						+ "UseBridges 0 Bridge obfs4 192.0.2.5:443");
		assertEquals(Arrays.asList(
				"Bridge obfs4 192.0.2.1:443 AAAA cert=BBBB iat-mode=0",
				"Bridge obfs4 192.0.2.2:443 CCCC",
				"bridge obfs4 192.0.2.3:443 DDDD",
				"Bridge snowflake 192.0.2.4:80 EEEE",
				"Bridge DisableNetwork 0",
				"Bridge UseBridges 0 Bridge obfs4 192.0.2.5:443"), bridges);
		for (String line : bridges) {
			assertTrue(line, line.toLowerCase(java.util.Locale.US)
					.startsWith("bridge "));
			assertFalse(line, line.contains("\n") || line.contains("\r"));
		}
	}

	@Test
	public void onlyPlausibleBridgeLinesAreAccepted() {
		assertTrue(TorBridgeConfigurator.isPlausibleBridgeLine(
				"obfs4 192.0.2.1:443 0123456789ABCDEF0123456789ABCDEF01234567"
						+ " cert=abc/def+ghi iat-mode=0"));
		assertTrue(TorBridgeConfigurator.isPlausibleBridgeLine(
				"192.0.2.1:9001 0123456789ABCDEF0123456789ABCDEF01234567"));
		assertTrue(TorBridgeConfigurator.isPlausibleBridgeLine(
				"snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72"
						+ " fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72"
						+ " url=https://example.invalid/ front=cdn.example"));
		assertTrue(TorBridgeConfigurator.isPlausibleBridgeLine(
				"[2001:db8::1]:443 0123456789ABCDEF0123456789ABCDEF01234567"));
		assertFalse(TorBridgeConfigurator.isPlausibleBridgeLine(""));
		assertFalse(TorBridgeConfigurator.isPlausibleBridgeLine("hello"));
		assertFalse(TorBridgeConfigurator.isPlausibleBridgeLine(
				"obfs4 192.0.2.1 nope"));
		assertFalse(TorBridgeConfigurator.isPlausibleBridgeLine(
				"obfs4 192.0.2.1:99999 nope"));
		assertFalse(TorBridgeConfigurator.isPlausibleBridgeLine(
				"obfs4 192.0.2.1:443 \"quoted\""));
		assertFalse(TorBridgeConfigurator.isPlausibleBridgeLine(
				"obfs4 192.0.2.1:443 x\ry"));
	}

	@Test
	public void emptyInputYieldsNoBridges() {
		assertTrue(TorBridgeConfigurator.parseCustomBridges("").isEmpty());
		assertTrue(TorBridgeConfigurator.parseCustomBridges("\n\r\n  \n")
				.isEmpty());
	}
}
