package com.professor.zerion.android.vault.net;

import org.zerionproject.core.socks.TcpTorSocksConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * NET-09: the loopback relay the native Monero wallet uses lets nothing
 * through to Tor without the process secret. The fake Tor behind the relay
 * records the credentials it is handed and echoes the first bytes after
 * the handshake, so the test can see both the refusal and the relay.
 */
public class TorSocksGateTest {

	private static final String SECRET = "process-secret";

	private ServerSocket fakeTor;
	private ExecutorService exec;
	private TorSocksGate gate;
	private final List<String> seenCredentials =
			Collections.synchronizedList(new ArrayList<>());

	@Before
	public void setUp() throws IOException {
		fakeTor = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
		exec = Executors.newCachedThreadPool();
		exec.execute(() -> {
			while (!fakeTor.isClosed()) {
				try {
					Socket s = fakeTor.accept();
					exec.execute(() -> serveTor(s));
				} catch (IOException e) {
					return;
				}
			}
		});
		gate = new TorSocksGate(new TcpTorSocksConnector(
				new InetSocketAddress("127.0.0.1", fakeTor.getLocalPort())),
				p -> TorSocksGate.constantTimeEquals(SECRET, p));
	}

	@After
	public void tearDown() throws IOException {
		gate.close();
		fakeTor.close();
		exec.shutdownNow();
	}

	private void serveTor(Socket s) {
		try (Socket socket = s) {
			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();
			byte[] greeting = readFully(in, 2);
			readFully(in, greeting[1]);
			out.write(new byte[] {5, 2});
			out.flush();
			readFully(in, 1);
			byte[] user = readFully(in, in.read());
			byte[] pass = readFully(in, in.read());
			seenCredentials.add(new String(user, StandardCharsets.UTF_8) + ":"
					+ new String(pass, StandardCharsets.UTF_8));
			out.write(new byte[] {1, 0});
			out.flush();
			byte[] buf = new byte[64];
			int n = in.read(buf);
			if (n > 0) {
				out.write(("tor:" + new String(buf, 0, n,
						StandardCharsets.UTF_8)).getBytes(
						StandardCharsets.UTF_8));
				out.flush();
			}
		} catch (IOException ignored) {
		}
	}

	private static byte[] readFully(InputStream in, int n)
			throws IOException {
		byte[] b = new byte[n];
		int off = 0;
		while (off < n) {
			int r = in.read(b, off, n - off);
			if (r < 0) throw new IOException("eof");
			off += r;
		}
		return b;
	}

	private Socket client() throws IOException {
		Socket c = new Socket();
		c.connect(new InetSocketAddress("127.0.0.1", gate.port()), 2000);
		c.setSoTimeout(5000);
		return c;
	}

	private static void auth(Socket c, String user, String pass)
			throws IOException {
		OutputStream out = c.getOutputStream();
		out.write(new byte[] {5, 1, 2});
		out.flush();
		byte[] u = user.getBytes(StandardCharsets.UTF_8);
		byte[] p = pass.getBytes(StandardCharsets.UTF_8);
		byte[] req = new byte[3 + u.length + p.length];
		req[0] = 1;
		req[1] = (byte) u.length;
		System.arraycopy(u, 0, req, 2, u.length);
		req[2 + u.length] = (byte) p.length;
		System.arraycopy(p, 0, req, 3 + u.length, p.length);
		out.write(req);
		out.flush();
	}

	@Test(timeout = 20_000)
	public void processSecretIsRelayedWithTheClientsUsername()
			throws Exception {
		try (Socket c = client()) {
			auth(c, "zx-wallet", SECRET);
			InputStream in = c.getInputStream();
			assertEquals(5, in.read());
			assertEquals(2, in.read());
			assertEquals(1, in.read());
			assertEquals(0, in.read());
			c.getOutputStream().write("hello".getBytes(StandardCharsets.UTF_8));
			c.getOutputStream().flush();
			byte[] buf = new byte[64];
			int n = in.read(buf);
			assertEquals("tor:hello", new String(buf, 0, n,
					StandardCharsets.UTF_8));
		}
		assertEquals(Collections.singletonList("zx-wallet:" + SECRET),
				seenCredentials);
	}

	@Test(timeout = 20_000)
	public void wrongPasswordIsRefusedBeforeTor() throws Exception {
		try (Socket c = client()) {
			auth(c, "zx-wallet", "guess");
			InputStream in = c.getInputStream();
			assertEquals(5, in.read());
			assertEquals(2, in.read());
			assertEquals(1, in.read());
			assertEquals("auth failure", 1, in.read());
			assertEquals("closed", -1, in.read());
		}
		assertTrue("nothing reached Tor", seenCredentials.isEmpty());
	}

	@Test(timeout = 20_000)
	public void noAuthMethodIsRefused() throws Exception {
		try (Socket c = client()) {
			c.getOutputStream().write(new byte[] {5, 1, 0});
			c.getOutputStream().flush();
			InputStream in = c.getInputStream();
			assertEquals(5, in.read());
			assertEquals(0xFF, in.read());
			assertEquals(-1, in.read());
		}
		assertTrue(seenCredentials.isEmpty());
	}

	@Test(timeout = 20_000)
	public void garbageIsDropped() throws Exception {
		try (Socket c = client()) {
			c.getOutputStream().write(new byte[] {4, 1, 0, 80, 1, 2, 3, 4, 0});
			c.getOutputStream().flush();
			assertEquals(-1, c.getInputStream().read());
		}
		assertTrue(seenCredentials.isEmpty());
	}
}
