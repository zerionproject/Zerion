package org.zerionproject.core.socks;

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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * NET-09: the SOCKS client speaks SOCKS5 with username/password over
 * whatever stream the connector opens, sends the destination as a domain
 * name without resolving it, hands data through once connected, and fails
 * closed when the listener refuses the credentials or the connection.
 */
public class TorSocksSocketTest {

	private ServerSocket server;
	private ExecutorService exec;
	private final List<String> hosts =
			Collections.synchronizedList(new ArrayList<>());
	private final List<String> credentials =
			Collections.synchronizedList(new ArrayList<>());
	private volatile boolean refuseAuth = false;
	private volatile int connectReply = 0;
	private final AtomicInteger opened = new AtomicInteger();

	@Before
	public void setUp() throws IOException {
		server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
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
	}

	private void serve(Socket s) {
		try (Socket socket = s) {
			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();
			byte[] greeting = readFully(in, 2);
			assertEquals(5, greeting[0]);
			byte[] methods = readFully(in, greeting[1]);
			assertEquals(1, methods.length);
			assertEquals(2, methods[0]);
			out.write(new byte[] {5, 2});
			out.flush();
			assertEquals(1, in.read());
			byte[] user = readFully(in, in.read());
			byte[] pass = readFully(in, in.read());
			credentials.add(new String(user, StandardCharsets.UTF_8) + ":"
					+ new String(pass, StandardCharsets.UTF_8));
			if (refuseAuth) {
				out.write(new byte[] {1, 1});
				out.flush();
				return;
			}
			out.write(new byte[] {1, 0});
			out.flush();
			byte[] head = readFully(in, 4);
			assertEquals(5, head[0]);
			assertEquals(1, head[1]);
			assertEquals("domain name, never an address", 3, head[3]);
			byte[] host = readFully(in, in.read());
			byte[] port = readFully(in, 2);
			hosts.add(new String(host, StandardCharsets.US_ASCII) + ":"
					+ (((port[0] & 0xFF) << 8) | (port[1] & 0xFF)));
			out.write(new byte[] {5, (byte) connectReply, 0, 1, 0, 0, 0, 0,
					0, 0});
			out.flush();
			if (connectReply != 0) return;
			byte[] buf = new byte[64];
			int n = in.read(buf);
			if (n > 0) {
				out.write(("echo:" + new String(buf, 0, n,
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

	private TorSocksConnector connector() {
		InetSocketAddress proxy =
				new InetSocketAddress("127.0.0.1", server.getLocalPort());
		TcpTorSocksConnector tcp = new TcpTorSocksConnector(proxy);
		return timeout -> {
			opened.incrementAndGet();
			return tcp.openProxySocket(timeout);
		};
	}

	private TorSocksSocket socket(String user, String pass) {
		return new TorSocksSocket(connector(), 2000, 2000, 2000, user, pass);
	}

	@Test(timeout = 20_000)
	public void connectsThroughTheListenerAndCarriesData() throws Exception {
		TorSocksSocket s = socket("dest.onion", "secret");
		assertFalse(s.isConnected());
		s.setSoTimeout(1500);
		s.connect(InetSocketAddress.createUnresolved("Dest.Onion", 443));
		assertTrue(s.isConnected());
		assertEquals("timeout carried onto the stream plus the extra",
				3500, s.getSoTimeout());
		s.getOutputStream().write("ping".getBytes(StandardCharsets.UTF_8));
		s.getOutputStream().flush();
		byte[] buf = new byte[64];
		int n = s.getInputStream().read(buf);
		assertEquals("echo:ping", new String(buf, 0, n,
				StandardCharsets.UTF_8));
		s.close();
		assertTrue(s.isClosed());
		assertEquals(1, opened.get());
		assertEquals(Collections.singletonList("dest.onion:secret"),
				credentials);
		assertEquals(Collections.singletonList("Dest.Onion:443"), hosts);
	}

	@Test(timeout = 20_000)
	public void refusedCredentialsFailClosed() throws Exception {
		refuseAuth = true;
		TorSocksSocket s = socket("u", "p");
		try {
			s.connect(InetSocketAddress.createUnresolved("x.onion", 80));
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("Authentication"));
		}
		assertFalse(s.isConnected());
		assertTrue(hosts.isEmpty());
	}

	@Test(timeout = 20_000)
	public void refusedConnectionFailsClosed() throws Exception {
		connectReply = 5;
		TorSocksSocket s = socket("u", "p");
		try {
			s.connect(InetSocketAddress.createUnresolved("x.onion", 80));
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("refused"));
		}
		assertFalse(s.isConnected());
	}

	@Test(timeout = 20_000)
	public void unreachableListenerFailsClosed() throws Exception {
		server.close();
		TorSocksSocket s = socket("u", "p");
		try {
			s.connect(InetSocketAddress.createUnresolved("x.onion", 80));
			fail();
		} catch (IOException expected) {
		}
		assertFalse(s.isConnected());
	}

	@Test(timeout = 20_000)
	public void factoryUsesTheDestinationAsUsername() throws Exception {
		IsolatingSocksSocketFactory f = new IsolatingSocksSocketFactory(
				connector(), 2000, 2000, 2000,
				new java.security.SecureRandom(),
				new SocksIsolationSecret(new java.security.SecureRandom()));
		Socket s = f.createSocket("ABC.onion", 80);
		s.close();
		assertEquals(1, credentials.size());
		assertTrue(credentials.get(0).startsWith("abc.onion:"));
		assertEquals(Collections.singletonList("ABC.onion:80"), hosts);
	}
}
