package org.zerionproject.transport.i2p;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Sam3SessionTest {

	private static final String FAKE_PRIVKEY = "PRIVKEYBASE64AAAA";
	private static final String FAKE_PUBDEST = "PUBDESTBASE64BBBB";

	private ServerSocket bridge;
	private ExecutorService exec;
	private int port;

	@Before
	public void setUp() throws IOException {
		bridge = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
		port = bridge.getLocalPort();
		exec = Executors.newCachedThreadPool();
		exec.execute(() -> {
			while (!bridge.isClosed()) {
				Socket s;
				try {
					s = bridge.accept();
				} catch (IOException e) {
					return;
				}
				exec.execute(() -> handle(s));
			}
		});
	}

	@After
	public void tearDown() throws IOException {
		bridge.close();
		exec.shutdownNow();
	}

	private void handle(Socket s) {
		try {
			InputStream in = s.getInputStream();
			OutputStream out = s.getOutputStream();
			String line;
			while ((line = readLine(in)) != null) {
				if (line.startsWith("HELLO")) {
					send(out, "HELLO REPLY RESULT=OK VERSION=3.1");
				} else if (line.startsWith("SESSION CREATE")) {
					send(out, "SESSION STATUS RESULT=OK DESTINATION="
							+ FAKE_PRIVKEY);
				} else if (line.startsWith("NAMING LOOKUP")) {
					send(out, "NAMING REPLY RESULT=OK NAME=ME VALUE="
							+ FAKE_PUBDEST);
				} else if (line.startsWith("STREAM CONNECT")) {
					if (line.contains("DESTINATION=UNREACHABLE")) {
						send(out, "STREAM STATUS RESULT=CANT_REACH_PEER");
					} else {
						send(out, "STREAM STATUS RESULT=OK");
					}
				} else if (line.startsWith("STREAM FORWARD")) {
					send(out, "STREAM STATUS RESULT=OK");
				}
			}
		} catch (IOException ignored) {
		}
	}

	@Test
	public void openCreatesSessionAndReturnsDestinationAndKey()
			throws IOException {
		Sam3Session session = Sam3Session.open("127.0.0.1", port, 5000,
				"zerion", null);
		assertEquals(FAKE_PUBDEST, session.getLocalDestination());
		assertEquals(FAKE_PRIVKEY, session.getPrivateKey());
		session.close();
	}

	@Test
	public void connectReturnsConnectedSocketOnOk() throws IOException {
		Sam3Session session = Sam3Session.open("127.0.0.1", port, 5000,
				"zerion", FAKE_PRIVKEY);
		Socket peer = session.connect("SOMEPEERDEST");
		assertNotNull(peer);
		assertTrue(peer.isConnected());
		peer.close();
		session.close();
	}

	@Test
	public void connectThrowsTypedResultOnUnreachable() throws IOException {
		Sam3Session session = Sam3Session.open("127.0.0.1", port, 5000,
				"zerion", FAKE_PRIVKEY);
		try {
			session.connect("UNREACHABLE");
			fail("expected Sam3Exception");
		} catch (Sam3Exception e) {
			assertEquals("CANT_REACH_PEER", e.getResult());
		} finally {
			session.close();
		}
	}

	@Test
	public void forwardSucceeds() throws IOException {
		Sam3Session session = Sam3Session.open("127.0.0.1", port, 5000,
				"zerion", FAKE_PRIVKEY);
		session.forwardTo(12345);
		session.close();
	}

	@Test
	public void connectRejectsNewlineInjection() throws IOException {
		Sam3Session session = Sam3Session.open("127.0.0.1", port, 5000,
				"zerion", FAKE_PRIVKEY);
		try {
			session.connect("GOODDEST\nDESTROY ID=zerion");
			fail("expected Sam3Exception");
		} catch (Sam3Exception e) {
			assertEquals("INVALID_KEY", e.getResult());
		} finally {
			session.close();
		}
	}

	@Test
	public void connectRejectsSpaceInjection() throws IOException {
		Sam3Session session = Sam3Session.open("127.0.0.1", port, 5000,
				"zerion", FAKE_PRIVKEY);
		try {
			session.connect("GOODDEST SILENT=true");
			fail("expected Sam3Exception");
		} catch (Sam3Exception e) {
			assertEquals("INVALID_KEY", e.getResult());
		} finally {
			session.close();
		}
	}

	@Test
	public void openRejectsMalformedPersistedKey() {
		try {
			Sam3Session.open("127.0.0.1", port, 5000, "zerion",
					"BADKEY\nEVIL");
			fail("expected Sam3Exception");
		} catch (IOException e) {
			assertTrue(e instanceof Sam3Exception);
		}
	}

	@Test
	public void replyParsesQuotedMessage() {
		Sam3Reply r = Sam3Reply.parse(
				"STREAM STATUS RESULT=I2P_ERROR MESSAGE=\"no lease set\"");
		assertEquals("STREAM STATUS", r.getType());
		assertEquals("I2P_ERROR", r.getResult());
		assertEquals("no lease set", r.get("MESSAGE"));
		assertTrue(!r.isOk());
	}

	private static void send(OutputStream out, String line) throws IOException {
		out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
		out.flush();
	}

	private static String readLine(InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		int b;
		while ((b = in.read()) != -1) {
			if (b == '\n') return sb.toString();
			if (b != '\r') sb.append((char) b);
		}
		return sb.length() == 0 ? null : sb.toString();
	}
}
