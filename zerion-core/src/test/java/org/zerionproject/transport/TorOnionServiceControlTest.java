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

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TorOnionServiceControlTest {

	private static final String ONION =
			"i66iurfyjjh5tqpqhq7luu6defbb5rs7mxzvqgryaabqkzkgmjhql7qd";
	private static final String KEY_BLOB =
			"ED25519-V3:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";

	private static final String CRLF = "\r\n";

	private ServerSocket server;
	private ExecutorService exec;
	private File torDir;
	private byte[] cookie;
	private volatile boolean refuseClientAuth = false;
	private volatile boolean noCredentialOnRemove;
	@javax.annotation.Nullable
	private volatile String torVersion = null;
	private volatile String powRefusal = null;
	@javax.annotation.Nullable
	private volatile String plainRefusal = null;
	@javax.annotation.Nullable
	private volatile String versionLine = null;
	private volatile boolean closeOnVersion = false;
	private final java.util.concurrent.atomic.AtomicInteger versionQueries =
			new java.util.concurrent.atomic.AtomicInteger();
	private final List<String> commands =
			Collections.synchronizedList(new ArrayList<>());

	@Before
	public void setUp() throws IOException {
		torDir = Files.createTempDirectory("tor-onion").toFile();
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

	private void serve(Socket s) {
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
					authenticated = line.substring("AUTHENTICATE ".length())
							.equalsIgnoreCase(StringUtils.toHexString(cookie));
					reply = authenticated ? "250 OK"
							: "515 Authentication failed";
				} else if (!authenticated) {
					reply = "514 Authentication required";
				} else if (line.equals("GETINFO version")) {
					versionQueries.incrementAndGet();
					if (closeOnVersion) return;
					String v = torVersion;
					if (versionLine != null) {
						out.write(versionLine + "\r\n");
						reply = "250 OK";
					} else if (v == null) {
						reply = "552 Unrecognized key \"version\"";
					} else {
						out.write("250-version=" + v + "\r\n");
						reply = "250 OK";
					}
				} else if (line.startsWith("ADD_ONION ")
						&& powRefusal != null
						&& line.contains("PoWDefensesEnabled")) {
					commands.add(line);
					reply = powRefusal;
				} else if (line.startsWith("ADD_ONION ")
						&& plainRefusal != null
						&& !line.contains("PoWDefensesEnabled")) {
					commands.add(line);
					reply = plainRefusal;
				} else if (line.startsWith("ADD_ONION ")) {
					commands.add(line);
					out.write("250-ServiceID=" + ONION + "\r\n");
					if (line.startsWith("ADD_ONION NEW:")) {
						out.write("250-PrivateKey=" + KEY_BLOB + "\r\n");
					}
					reply = "250 OK";
				} else if (line.startsWith("DEL_ONION ")) {
					commands.add(line);
					reply = "250 OK";
				} else if (line.startsWith("ONION_CLIENT_AUTH_ADD ")) {
					commands.add(line);
					reply = refuseClientAuth ? "512 Invalid key"
							: "251 Client for onion existed and replaced";
				} else if (line.startsWith("ONION_CLIENT_AUTH_REMOVE ")) {
					commands.add(line);
					reply = noCredentialOnRemove
							? "251 No credentials for \"" + ONION + "\""
							: "250 OK";
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

	private TorOnionServiceControl control() {
		return new TorOnionServiceControl(torDir,
				() -> new TorControl.SocketConnection(server.getLocalPort()));
	}

	private static byte[] key(int fill) {
		byte[] k = new byte[32];
		Arrays.fill(k, (byte) fill);
		return k;
	}

	@Test(timeout = 20_000)
	public void testPublishesADetachedServiceWithOneEntryPerClientKey()
			throws Exception {
		org.zerionproject.core.plugin.tor.auth.OnionServiceControl.Published p = control().publish(null,
				4321, 80, asList(key(1), key(2)));
		assertEquals(ONION, p.onion);
		assertEquals(KEY_BLOB, p.privateKey);
		assertEquals(1, commands.size());
		String expected = "ADD_ONION NEW:ED25519-V3 Flags=Detach,V3Auth"
				+ " ClientAuthV3=" + TorOnionServiceControl
				.encodeClientPublicKey(key(1))
				+ " ClientAuthV3=" + TorOnionServiceControl
				.encodeClientPublicKey(key(2))
				+ " Port=80,127.0.0.1:4321";
		assertEquals(expected, commands.get(0));
		String b32 = TorOnionServiceControl.encodeClientPublicKey(key(1));
		assertEquals(52, b32.length());
		assertTrue(b32.matches("[A-Z2-7]{52}"));
	}

	@Test(timeout = 20_000)
	public void anInboxAllowsOneStreamPerCircuitAndOneClientKey()
			throws Exception {
		TorOnionServiceControl.InboxPublished p =
				control().publishInbox(KEY_BLOB, "unix:/data/x/inbox", 80,
						key(4));
		assertEquals(ONION, p.onion);
		assertEquals("ADD_ONION " + KEY_BLOB
				+ " Flags=Detach,V3Auth,MaxStreamsCloseCircuit MaxStreams=1"
				+ " ClientAuthV3="
				+ TorOnionServiceControl.encodeClientPublicKey(key(4))
				+ " Port=80,unix:/data/x/inbox", commands.get(0));
	}

	private static final String POW =
			" PoWDefensesEnabled=1 PoWQueueRate=50 PoWQueueBurst=250";

	private String inboxCommand(boolean pow) {
		return "ADD_ONION " + KEY_BLOB
				+ " Flags=Detach,V3Auth,MaxStreamsCloseCircuit MaxStreams=1"
				+ (pow ? POW : "") + " ClientAuthV3="
				+ TorOnionServiceControl.encodeClientPublicKey(key(4))
				+ " Port=80,unix:/data/x/inbox";
	}

	private TorOnionServiceControl.InboxPublished publishInbox()
			throws IOException {
		return control().publishInbox(KEY_BLOB, "unix:/data/x/inbox", 80,
				key(4));
	}

	@Test(timeout = 20_000)
	public void anInboxAsksForProofOfWorkWhenTorSupportsIt()
			throws Exception {
		torVersion = "0.4.9.14 (git-0123456789abcdef)";
		TorOnionServiceControl.InboxPublished p = publishInbox();
		assertEquals(ONION, p.onion);
		assertTrue(p.powProtected);
		assertEquals(1, versionQueries.get());
		assertEquals(singletonList(inboxCommand(true)), commands);
	}

	@Test(timeout = 20_000)
	public void otherOnionsNeverAskForProofOfWork() throws Exception {
		torVersion = "0.4.9.14";
		control().publish(null, 4321, 80, asList(key(1)));
		control().publish(KEY_BLOB, "unix:/data/x/channel", 80,
				asList(key(2)));
		assertEquals(2, commands.size());
		for (String c : commands) {
			assertFalse(c, c.contains("PoW"));
		}
		assertEquals(0, versionQueries.get());
	}

	@Test(timeout = 20_000)
	public void anInboxIsRepublishedWithoutProofOfWorkWhenTorRefusesIt()
			throws Exception {
		torVersion = "0.4.9.14";
		for (String refusal : new String[] {
				"552 Unrecognized key \"PoWDefensesEnabled\"",
				"512 Unrecognized keyword argument PoWDefensesEnabled"}) {
			commands.clear();
			powRefusal = refusal;
			TorOnionServiceControl.InboxPublished p = publishInbox();
			assertEquals(ONION, p.onion);
			assertFalse(refusal, p.powProtected);
			assertEquals(refusal, asList(inboxCommand(true),
					inboxCommand(false)), commands);
		}
	}

	@Test(timeout = 20_000)
	public void anotherRefusalIsNotRetriedWithoutProofOfWork()
			throws Exception {
		torVersion = "0.4.9.14";
		for (String refusal : new String[] {"512 Invalid argument",
				"552 Invalid key type", "513 Unrecognized command"}) {
			commands.clear();
			powRefusal = refusal;
			try {
				publishInbox();
				fail(refusal);
			} catch (IOException expected) {
			}
			assertEquals(refusal, singletonList(inboxCommand(true)), commands);
		}
	}

	@Test(timeout = 20_000)
	public void aRefusedPlainRepublishFailsAndClaimsNoProofOfWork()
			throws Exception {
		torVersion = "0.4.9.14";
		powRefusal = "552 Unrecognized key \"PoWDefensesEnabled\"";
		plainRefusal = "512 Invalid argument";
		try {
			publishInbox();
			fail();
		} catch (IOException expected) {
		}
		assertEquals(asList(inboxCommand(true), inboxCommand(false)),
				commands);
	}

	@Test(timeout = 20_000)
	public void aConnectionClosedOnGetInfoStillPublishesWithoutProofOfWork()
			throws Exception {
		closeOnVersion = true;
		TorOnionServiceControl.InboxPublished p = publishInbox();
		assertEquals(ONION, p.onion);
		assertFalse(p.powProtected);
		assertEquals(1, versionQueries.get());
		assertEquals(singletonList(inboxCommand(false)), commands);
	}

	@Test(timeout = 20_000)
	public void aVersionLineInAnotherFormGetsNoProofOfWork()
			throws Exception {
		versionLine = "250-Tor version 0.4.9.14.";
		TorOnionServiceControl.InboxPublished p = publishInbox();
		assertEquals(ONION, p.onion);
		assertFalse(p.powProtected);
		assertEquals(singletonList(inboxCommand(false)), commands);
	}

	@Test
	public void onlyARefusalOfTheKeywordsCountsAsOne() {
		for (String yes : new String[] {
				"552 Unrecognized key \"PoWDefensesEnabled\"",
				"512 Unrecognized keyword argument PoWQueueRate",
				"513 unrecognized keyword"}) {
			assertTrue(yes, TorOnionServiceControl.refusesPowKeywords(
					singletonList(yes)));
		}
		for (String no : new String[] {"552 Invalid key type",
				"512 Invalid argument", "550 PoW something", "250 OK"}) {
			assertFalse(no, TorOnionServiceControl.refusesPowKeywords(
					singletonList(no)));
		}
		assertFalse(TorOnionServiceControl.refusesPowKeywords(
				Collections.emptyList()));
	}

	@Test(timeout = 20_000)
	public void anOlderOrUnknownTorGetsNoProofOfWorkKeywords()
			throws Exception {
		for (String v : new String[] {"0.4.8.16", "0.4.9.1-alpha", null}) {
			commands.clear();
			torVersion = v;
			TorOnionServiceControl.InboxPublished p = publishInbox();
			assertFalse(String.valueOf(v), p.powProtected);
			assertEquals(singletonList(inboxCommand(false)), commands);
		}
	}

	@Test
	public void theVersionGateStartsAt0492() {
		for (String yes : new String[] {"0.4.9.2-alpha", "0.4.9.2",
				"0.4.9.14 (git-abc)", "0.4.10.0", "0.5.0.1", "1.0.0.0"}) {
			assertTrue(yes, TorOnionServiceControl.supportsPow(asList(
					"250-version=" + yes, "250 OK")));
		}
		for (String no : new String[] {"0.4.9.1", "0.4.8.99", "0.3.5.17",
				"0.4.9", "junk"}) {
			assertFalse(no, TorOnionServiceControl.supportsPow(asList(
					"250-version=" + no, "250 OK")));
		}
		assertFalse(TorOnionServiceControl.supportsPow(asList(
				"250-version=0.4.9.14", "551 Internal error")));
		assertFalse(TorOnionServiceControl.supportsPow(
				Collections.emptyList()));
	}

	@Test(timeout = 20_000)
	public void testRepublishesWithAnExistingKey() throws Exception {
		org.zerionproject.core.plugin.tor.auth.OnionServiceControl.Published p = control().publish(
				KEY_BLOB, 4321, 80, asList(key(3)));
		assertEquals(KEY_BLOB, p.privateKey);
		assertTrue(commands.get(0).startsWith("ADD_ONION " + KEY_BLOB
				+ " Flags=Detach,V3Auth ClientAuthV3="));
	}

	@Test(timeout = 20_000)
	public void testRefusesToPublishWithoutClientKeys() throws Exception {
		try {
			control().publish(null, 4321, 80, Collections.emptyList());
			fail();
		} catch (IllegalArgumentException expected) {
		}
		assertTrue(commands.isEmpty());
	}

	@Test(timeout = 20_000)
	public void testRegistersAClientKeyInBase64AndAcceptsReplaced()
			throws Exception {
		control().addClientKey(ONION, key(7));
		assertEquals("ONION_CLIENT_AUTH_ADD " + ONION + " x25519:"
				+ java.util.Base64.getEncoder().encodeToString(key(7)),
				commands.get(0));
	}

	@Test(timeout = 20_000)
	public void testFailsClosedWhenTorRefusesAClientKey() throws Exception {
		refuseClientAuth = true;
		try {
			control().addClientKey(ONION, key(7));
			fail();
		} catch (IOException expected) {
		}
	}

	@Test(timeout = 20_000)
	public void testRemovesServiceAndClientKey() throws Exception {
		control().remove(ONION);
		control().removeClientKey(ONION);
		assertEquals(asList("DEL_ONION " + ONION,
				"ONION_CLIENT_AUTH_REMOVE " + ONION), commands);
	}

	@Test(timeout = 20_000)
	public void testRemovingAnAbsentClientKeyIsNotAFailure() throws Exception {
		noCredentialOnRemove = true;
		control().removeClientKey(ONION);
		assertEquals(singletonList("ONION_CLIENT_AUTH_REMOVE " + ONION),
				commands);
	}

	@Test
	public void testClassifiesEveryDocumentedClientAuthReply() throws Exception {
		for (String ok : new String[] {"250 OK", "251 Client for onion existed and replaced", "252 Added client auth and decrypted a cached descriptor"}) {
			TorOnionServiceControl.classifyClientAuthReply(
					Collections.singletonList(ok));
		}
		try {
			TorOnionServiceControl.classifyClientAuthReply(
					Collections.singletonList("451 Cannot decrypt: too many clients"));
			fail();
		} catch (org.zerionproject.core.plugin.tor.auth.OnionServiceControl
				.CapacityException expected) {
		}
		for (String bad : new String[] {"512 Invalid v3 address", "551 Client name conflict", "552 Unrecognized key type", "253 Unexpected positive", "550 Something else", "OK"}) {
			try {
				TorOnionServiceControl.classifyClientAuthReply(
						Collections.singletonList(bad));
				fail(bad);
			} catch (IOException expected) {
			}
		}
	}

	@Test(timeout = 20_000)
	public void testForwardsTheAuthorizedServiceToAUnixSocket()
			throws Exception {
		control().publish(null, "unix:/data/user/0/app/files/zo/0a1b2c3d",
				80, asList(key(1)));
		assertTrue(commands.get(0), commands.get(0).endsWith(
				" Port=80,unix:/data/user/0/app/files/zo/0a1b2c3d"));
	}

	@Test(timeout = 20_000)
	public void testATargetThatCouldEndTheCommandNeverReachesTor()
			throws Exception {
		for (String bad : new String[] {"unix:/a b",
				"unix:/a" + CRLF + "GETINFO x", "unix:/a,80", "unix:relative", "unix:\"/a\"",
				"10.0.0.1:80", "127.0.0.1:0", "127.0.0.1:70000", ""}) {
			try {
				control().publish(null, bad, 80, asList(key(1)));
				fail(bad);
			} catch (IllegalArgumentException expected) {
			}
		}
		assertTrue(commands.isEmpty());
	}

	@Test
	public void testRejectsAMalformedOnion() throws Exception {
		try {
			control().remove("not-an-onion");
			fail();
		} catch (IllegalArgumentException expected) {
		}
	}
}
