package org.zerionproject.transport;

import org.zerionproject.core.util.StringUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Drives the configurator against a loopback server that speaks the Tor
 * control protocol subset it uses. The server starts from the configuration
 * the shipped wrapper writes (a bare SocksPort and ConnectionPadding 0) and
 * can be told to honour or ignore SETCONF, so the tests show that the
 * configurator only passes when Tor itself reports the isolation flags and
 * padding as active, and fails closed in every other case.
 */
public class TorPrivacyConfiguratorImplTest {

	private static final int SOCKS_PORT = 59050;

	private ServerSocket server;
	private ExecutorService exec;
	private File torDir;
	private byte[] cookie;
	private volatile boolean honourSocksFlags = true;
	private volatile boolean honourPadding = true;
	private volatile boolean acceptCookie = true;
	private final List<String> setconfs =
			Collections.synchronizedList(new ArrayList<>());

	@Before
	public void setUp() throws IOException {
		torDir = Files.createTempDirectory("tor-privacy").toFile();
		File dataDir = new File(torDir, ".tor");
		assertTrue(dataDir.mkdirs());
		cookie = new byte[32];
		new SecureRandom().nextBytes(cookie);
		try (FileOutputStream out = new FileOutputStream(
				new File(dataDir, "control_auth_cookie"))) {
			out.write(cookie);
		}
		server = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"));
		exec = Executors.newCachedThreadPool();
		exec.execute(() -> {
			while (!server.isClosed()) {
				try {
					Socket s = server.accept();
					exec.execute(() -> serve(s));
				} catch (IOException e) {
					return;
				}
			}
		});
	}

	@After
	public void tearDown() throws IOException {
		server.close();
		exec.shutdownNow();
		for (File f : new File(torDir, ".tor").listFiles()) f.delete();
		new File(torDir, ".tor").delete();
		torDir.delete();
	}

	/** State of the fake Tor: what the shipped torrc produces at start. */
	private void serve(Socket s) {
		String socksLine = String.valueOf(SOCKS_PORT);
		String padding = "0";
		boolean authenticated = false;
		try (Socket socket = s) {
			BufferedReader in = new BufferedReader(new InputStreamReader(
					socket.getInputStream(), StandardCharsets.US_ASCII));
			Writer out = new OutputStreamWriter(socket.getOutputStream(),
					StandardCharsets.US_ASCII);
			String line;
			while ((line = in.readLine()) != null) {
				String reply;
				if (line.startsWith("AUTHENTICATE ")) {
					String hex = line.substring("AUTHENTICATE ".length());
					authenticated = acceptCookie && hex.equalsIgnoreCase(
							StringUtils.toHexString(cookie));
					reply = authenticated ? "250 OK"
							: "515 Authentication failed";
				} else if (!authenticated) {
					reply = "514 Authentication required";
				} else if (line.startsWith("SETCONF ")) {
					setconfs.add(line);
					int q1 = line.indexOf('"'), q2 = line.lastIndexOf('"');
					if (honourSocksFlags && q1 >= 0 && q2 > q1) {
						socksLine = line.substring(q1 + 1, q2);
					}
					if (honourPadding && line.contains("ConnectionPadding=1")) {
						padding = "1";
					}
					reply = "250 OK";
				} else if (line.equals("GETCONF SocksPort")) {
					reply = "250 SocksPort=" + socksLine;
				} else if (line.equals("GETCONF ConnectionPadding")) {
					reply = "250 ConnectionPadding=" + padding;
				} else if (line.equals("QUIT")) {
					out.write("250 closing connection\r\n");
					out.flush();
					return;
				} else {
					reply = "510 Unrecognized command";
				}
				out.write(reply + "\r\n");
				out.flush();
			}
		} catch (IOException ignored) {
		}
	}

	private TorPrivacyConfiguratorImpl configurator() {
		return new TorPrivacyConfiguratorImpl(torDir, SOCKS_PORT,
				server.getLocalPort());
	}

	@Test(timeout = 20_000)
	public void testPassesOnlyAfterTorReportsIsolationAndPadding()
			throws Exception {
		configurator().applyAndVerify();
		assertEquals(1, setconfs.size());
		String applied = setconfs.get(0);
		assertTrue(applied.contains("SocksPort=\"" + SOCKS_PORT
				+ " IsolateSOCKSAuth IsolateClientAddr IsolateDestAddr\""));
		assertTrue(applied.contains("ConnectionPadding=1"));
	}

	@Test(timeout = 20_000)
	public void testFailsClosedWhenTorKeepsTheBareSocksPort() {
		honourSocksFlags = false;
		try {
			configurator().applyAndVerify();
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("isolation"));
		}
	}

	@Test(timeout = 20_000)
	public void testFailsClosedWhenPaddingStaysOff() {
		honourPadding = false;
		try {
			configurator().applyAndVerify();
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("padding"));
		}
	}

	@Test(timeout = 20_000)
	public void testFailsClosedWhenAuthenticationIsRefused() {
		acceptCookie = false;
		try {
			configurator().applyAndVerify();
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("authentication"));
		}
		assertTrue(setconfs.isEmpty());
	}

	@Test(timeout = 20_000)
	public void testFailsClosedWithoutTheControlCookie() throws Exception {
		assertTrue(new File(torDir, ".tor/control_auth_cookie").delete());
		try {
			configurator().applyAndVerify();
			fail();
		} catch (IOException expected) {
		}
		assertTrue(setconfs.isEmpty());
	}

	@Test
	public void testEffectiveSocksLineParsing() {
		List<String> ok = Arrays.asList("250 SocksPort=59050 IsolateSOCKSAuth"
				+ " IsolateClientAddr IsolateDestAddr");
		assertTrue(TorPrivacyConfiguratorImpl.socksIsolationActive(ok, 59050));
		List<String> withAddress = Arrays.asList(
				"250-SocksPort=127.0.0.1:59050 isolatesocksauth"
						+ " IsolateClientAddr IsolateDestAddr",
				"250 SocksPort=9999");
		assertTrue(TorPrivacyConfiguratorImpl.socksIsolationActive(
				withAddress, 59050));
		assertFalse("other port", TorPrivacyConfiguratorImpl
				.socksIsolationActive(ok, 59051));
		assertFalse("bare port", TorPrivacyConfiguratorImpl
				.socksIsolationActive(Arrays.asList("250 SocksPort=59050"),
						59050));
		assertFalse("missing flag", TorPrivacyConfiguratorImpl
				.socksIsolationActive(Arrays.asList(
						"250 SocksPort=59050 IsolateClientAddr IsolateDestAddr"),
						59050));
		assertFalse("negated flag", TorPrivacyConfiguratorImpl
				.socksIsolationActive(Arrays.asList("250 SocksPort=59050"
						+ " IsolateSOCKSAuth IsolateClientAddr IsolateDestAddr"
						+ " NoIsolateSOCKSAuth"), 59050));
		assertTrue(TorPrivacyConfiguratorImpl.paddingActive(
				Arrays.asList("250 ConnectionPadding=1")));
		assertFalse(TorPrivacyConfiguratorImpl.paddingActive(
				Arrays.asList("250 ConnectionPadding=0")));
		assertFalse(TorPrivacyConfiguratorImpl.paddingActive(
				Arrays.asList("250 ConnectionPadding=auto")));
	}
}
