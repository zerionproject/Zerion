package org.zerionproject.tor;

import org.junit.Test;
import org.zerionproject.transport.TorBridgeConfigurator;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class BridgeLinesTest {

	@Test
	public void everyBundledObfs4AndPlainLinePassesTheRemoteListCheck() {
		for (String letter : new String[] {"d", "n"}) {
			List<String> lines = BridgeListFixtures.bundled(letter);
			assertFalse(letter, lines.isEmpty());
			for (String line : lines) {
				assertTrue(line, BridgeLines.isObfs4Line(line));
				assertFalse(line, BridgeLines.isVanillaLine(line));
				assertTrue(line, TorBridgeConfigurator.isPlausibleBridgeLine(line));
				assertEquals(line, line.split(" ")[2],
						BridgeLines.fingerprint(line));
				assertEquals(line, line.split(" ")[2],
						BridgeLines.fingerprint("Bridge " + line));
			}
		}
		List<String> plain = BridgeListFixtures.bundled("v");
		assertFalse(plain.isEmpty());
		for (String line : plain) {
			assertTrue(line, BridgeLines.isVanillaLine(line));
			assertFalse(line, BridgeLines.isObfs4Line(line));
			assertTrue(line, TorBridgeConfigurator.isPlausibleBridgeLine(line));
			assertEquals(line.split(" ")[1], BridgeLines.fingerprint(line));
		}
	}

	@Test
	public void snowflakeAndMeekLinesAreNeverManagedLines() {
		for (String letter : new String[] {"s", "m"}) {
			for (String line : BridgeListFixtures.bundled(letter)) {
				assertFalse(line, BridgeLines.isObfs4Line(line));
				assertFalse(line, BridgeLines.isVanillaLine(line));
				assertNull(line, BridgeLines.fingerprint(line));
				assertTrue(line, BridgeLines.isWellFormed(line));
			}
		}
	}

	@Test
	public void theRelayNameOfAnOrConnectionGivesItsFingerprint() {
		String fp = "0123456789ABCDEF0123456789ABCDEF01234567";
		assertEquals(fp, BridgeLines.relayFingerprint("$" + fp));
		assertEquals(fp, BridgeLines.relayFingerprint("$" + fp + "~nick"));
		assertEquals(fp, BridgeLines.relayFingerprint("$" + fp + "=nick"));
		assertEquals(fp, BridgeLines.relayFingerprint(
				"$" + fp.toLowerCase(java.util.Locale.US)));
		assertNull(BridgeLines.relayFingerprint("45.33.1.1:443"));
		assertNull(BridgeLines.relayFingerprint(fp));
		assertNull(BridgeLines.relayFingerprint("$" + fp.substring(1)));
		assertNull(BridgeLines.relayFingerprint("$" + fp + "0"));
		assertNull(BridgeLines.relayFingerprint("$" + fp.replace('A', 'G')));
		assertNull(BridgeLines.relayFingerprint(""));
	}

	@Test
	public void onlyPublicAddressesCount() {
		String[] ok = {"1.1.1.1:443", "45.33.1.1:1", "223.255.255.255:65535",
				"172.15.0.1:80", "172.32.0.1:80", "100.63.255.255:80",
				"100.128.0.0:80", "192.0.3.1:80", "198.17.0.1:80",
				"198.20.0.1:80"};
		for (String a : ok) assertTrue(a, BridgeLines.isPublicIpv4Address(a));
		String[] no = {"0.1.2.3:80", "10.0.0.1:80", "100.64.0.1:80",
				"100.127.255.255:80", "127.0.0.1:80", "169.254.1.1:80",
				"172.16.0.1:80", "172.31.255.255:80", "192.0.0.1:80",
				"192.0.2.1:80", "192.88.99.1:80", "192.168.0.1:80",
				"198.18.0.1:80", "198.19.255.255:80", "198.51.100.1:80",
				"203.0.113.1:80", "224.0.0.1:80", "239.1.1.1:80",
				"240.0.0.1:80", "255.255.255.255:80", "1.1.1.1:0",
				"1.1.1.1:65536", "1.1.1.1", "1.1.1:80", "1.1.1.1.1:80",
				"01.1.1.1:80", "1.1.1.1:080", "a.b.c.d:80", "[::1]:80"};
		for (String a : no) assertFalse(a, BridgeLines.isPublicIpv4Address(a));
	}
}
