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
		assertEquals(4, plugins.size());
		for (String p : plugins) assertTrue(p, p.endsWith(" exec " + lyrebird));
	}

	@Test
	public void webtunnelBridgesHaveATransport() throws Exception {
		TestTorWrapper w = wrapper();
		String lyrebird = w.getLyrebirdExecutableFile().getAbsolutePath();
		assertTrue(lines(w.torrc()).contains(
				"ClientTransportPlugin webtunnel exec " + lyrebird));
	}

	private static final String CONTROL_SOCKET =
			"/data/user/0/com.professor.zerion/files/zs/0123456789abc";

	private TestTorWrapper unixWrapper() throws Exception {
		File dir = tmp.newFolder("tor-unix");
		return new TestTorWrapper(dir, CONTROL_SOCKET, (t, l) -> {
		});
	}

	@Test
	public void withAUnixControlSocketTorOpensNoTcpListener()
			throws Exception {
		List<String> l = lines(unixWrapper().torrc());
		assertEquals(Arrays.asList("ControlPort unix:" + CONTROL_SOCKET),
				startingWith(l, "ControlPort "));
		assertEquals(Arrays.asList("SocksPort 0"),
				startingWith(l, "SocksPort "));
		for (String line : l) {
			assertTrue(line, !line.contains("9050"));
			assertTrue(line, !line.contains("9051"));
			assertTrue(line, !line.startsWith("DNSPort"));
			assertTrue(line, !line.startsWith("TransPort"));
			assertTrue(line, !line.startsWith("ORPort"));
			assertTrue(line, !line.startsWith("HTTPTunnelPort"));
		}
		assertTrue(l.contains("CookieAuthentication 1"));
		assertTrue(l.contains("DisableNetwork 1"));
	}

	@Test
	public void aBadControlSocketPathFailsTheStartBeforeTorRuns()
			throws Exception {
		TestTorWrapper w = new TestTorWrapper(tmp.newFolder("tor-bad-start"),
				"/data/zs/a b", (t, l) -> {
		});
		try {
			w.start();
			throw new AssertionError("Tor started with a bad control path");
		} catch (java.io.IOException expected) {
		}
		assertTrue(w.launched == null);
		assertEquals(TorWrapper.TorState.STOPPED, w.getTorState());
	}

	@Test(expected = IllegalArgumentException.class)
	public void aControlSocketPathThatCouldEndTheLineIsRefused()
			throws Exception {
		new TestTorWrapper(tmp.newFolder("tor-bad"),
				"/data/zs/ctl\nControlPort 9051", (t, l) -> {
		}).torrc();
	}

	@Test
	public void nothingIsLoggedByTor() throws Exception {
		for (String l : lines(wrapper().torrc())) {
			assertTrue(l, !l.startsWith("Log "));
		}
	}
}
