package org.zerionproject.tor;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * SC-TOR-01: the configuration Tor is started with carries the isolation
 * and padding hardening itself, rather than relying on the transport to
 * add it afterwards over the control port.
 */
public class TorConfigurationTest {

	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private TestTorWrapper wrapper() throws Exception {
		File dir = tmp.newFolder("tor");
		return new TestTorWrapper(dir, 9050, 9051, (t, l) -> {
		});
	}

	private static List<String> lines(String torrc) {
		List<String> out = new ArrayList<>();
		for (String l : torrc.split("\n")) {
			if (!l.isEmpty()) out.add(l);
		}
		return out;
	}

	private static List<String> startingWith(List<String> lines, String p) {
		List<String> out = new ArrayList<>();
		for (String l : lines) if (l.startsWith(p)) out.add(l);
		return out;
	}

	@Test
	public void theOnlySocksListenerCarriesEveryIsolationFlag()
			throws Exception {
		List<String> socks = startingWith(lines(wrapper().torrc()),
				"SocksPort ");
		assertEquals(1, socks.size());
		List<String> tokens = Arrays.asList(socks.get(0).split(" "));
		assertEquals("9050", tokens.get(1));
		for (String flag : AbstractTorWrapper.SOCKS_ISOLATION_FLAGS) {
			assertTrue(flag, tokens.contains(flag));
		}
		assertTrue(tokens.contains("IsolateSOCKSAuth"));
		assertTrue(tokens.contains("IsolateClientAddr"));
		assertTrue(tokens.contains("IsolateDestAddr"));
	}

	@Test
	public void noListenerNegatesAnIsolationFlag() throws Exception {
		for (String l : lines(wrapper().torrc())) {
			for (String flag : AbstractTorWrapper.SOCKS_ISOLATION_FLAGS) {
				assertTrue(l, !l.contains("No" + flag));
			}
		}
	}

	@Test
	public void connectionPaddingIsOnFromTheStart() throws Exception {
		List<String> l = lines(wrapper().torrc());
		assertTrue(l.contains("ConnectionPadding 1"));
		assertTrue(l.contains("ReducedConnectionPadding 0"));
		assertEquals(1, startingWith(l, "ConnectionPadding ").size());
	}

	@Test
	public void theNetworkStaysOffUntilTheTransportEnablesIt()
			throws Exception {
		List<String> l = lines(wrapper().torrc());
		assertTrue(l.contains("DisableNetwork 1"));
		assertTrue(l.contains("SafeSocks 1"));
		assertTrue(l.contains("CookieAuthentication 1"));
		assertTrue(l.contains("ControlPort 9051"));
	}

	@Test
	public void pluggableTransportsRunTheVerifiedLyrebird() throws Exception {
		TestTorWrapper w = wrapper();
		String lyrebird = w.getLyrebirdExecutableFile().getAbsolutePath();
		List<String> plugins = startingWith(lines(w.torrc()),
				"ClientTransportPlugin ");
		assertEquals(3, plugins.size());
		for (String p : plugins) assertTrue(p, p.endsWith(" exec " + lyrebird));
	}

	@Test
	public void nothingIsLoggedByTor() throws Exception {
		for (String l : lines(wrapper().torrc())) {
			assertTrue(l, !l.startsWith("Log "));
		}
	}
}
