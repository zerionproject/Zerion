package org.zerionproject.transport;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The only Tor configuration this side composes from user input is the
 * custom bridge list. Every line handed to Tor must be a single Bridge line:
 * a line that already carries the keyword keeps it once, any other line gets
 * it, blank lines vanish, both line endings split, and no line can smuggle a
 * second option because the keyword is always the first word.
 */
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
	public void emptyInputYieldsNoBridges() {
		assertTrue(TorBridgeConfigurator.parseCustomBridges("").isEmpty());
		assertTrue(TorBridgeConfigurator.parseCustomBridges("\n\r\n  \n")
				.isEmpty());
	}
}
