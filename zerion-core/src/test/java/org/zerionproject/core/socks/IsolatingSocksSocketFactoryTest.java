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
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/**
 * Runs a loopback SOCKS5 server that records the credentials each client
 * presents, and checks that the isolating factory authenticates every
 * connection with a username that names the destination, so Tor's SOCKS
 * isolation places different destinations on different circuits.
 */
public class IsolatingSocksSocketFactoryTest {

	private static final class Credentials {
		final String username, password, destination;

		Credentials(String username, String password, String destination) {
			this.username = username;
			this.password = password;
			this.destination = destination;
		}
	}

	private ServerSocket server;
	private ExecutorService exec;
	private final List<Credentials> seen =
			Collections.synchronizedList(new ArrayList<>());

	@Before
	public void startFakeProxy() throws IOException {
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
	public void stopFakeProxy() throws IOException {
		server.close();
		exec.shutdownNow();
	}

	private void serve(Socket s) {
		try (Socket socket = s) {
			InputStream in = socket.getInputStream();
			OutputStream out = socket.getOutputStream();
			int version = in.read();
			int methodCount = in.read();
			byte[] methods = readFully(in, methodCount);
			assertEquals(5, version);
			boolean offersAuth = false;
			for (byte m : methods) if (m == 2) offersAuth = true;
			assertTrue("client must offer username/password", offersAuth);
			out.write(new byte[] {5, 2});
			out.flush();
			assertEquals(1, in.read());
			byte[] user = readFully(in, in.read());
			byte[] pass = readFully(in, in.read());
			out.write(new byte[] {1, 0});
			out.flush();
			byte[] head = readFully(in, 4);
			assertEquals(5, head[0]);
			assertEquals(1, head[1]);
			assertEquals(3, head[3]);
			byte[] host = readFully(in, in.read());
			readFully(in, 2);
			seen.add(new Credentials(
					new String(user, StandardCharsets.UTF_8),
					new String(pass, StandardCharsets.UTF_8),
					new String(host, StandardCharsets.US_ASCII)));
			out.write(new byte[] {5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
			out.flush();
			in.read();
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

	private IsolatingSocksSocketFactory factory() {
		return factory(new SocksIsolationSecret(new SecureRandom()));
	}

	private IsolatingSocksSocketFactory factory(SocksIsolationSecret secret) {
		return new IsolatingSocksSocketFactory(new TcpTorSocksConnector(
				new InetSocketAddress("127.0.0.1", server.getLocalPort())),
				2000, 2000, 2000, new SecureRandom(), secret);
	}

	private Credentials connect(IsolatingSocksSocketFactory f, String host)
			throws IOException {
		int before = seen.size();
		Socket s = f.createSocket(host, 443);
		s.close();
		long deadline = System.currentTimeMillis() + 5000;
		while (seen.size() <= before && System.currentTimeMillis() < deadline) {
			Thread.yield();
		}
		assertEquals(before + 1, seen.size());
		return seen.get(before);
	}

	@Test(timeout = 20_000)
	public void everyDestinationGetsItsOwnUsername() throws Exception {
		IsolatingSocksSocketFactory f = factory();
		Credentials a = connect(f, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.onion");
		Credentials b = connect(f, "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.onion");
		Credentials a2 = connect(f, "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA.onion");
		assertEquals(a.destination.toLowerCase(), a.username);
		assertEquals(b.destination.toLowerCase(), b.username);
		assertNotEquals(a.username, b.username);
		assertEquals("one destination shares one circuit", a.username,
				a2.username);
		assertEquals(a.password, b.password);
		assertTrue(a.password.length() >= 32);
	}

	@Test(timeout = 20_000)
	public void freshProcessSecretMeansFreshCircuits() throws Exception {
		Credentials a = connect(factory(), "cccccccccccccccc.onion");
		Credentials b = connect(factory(), "cccccccccccccccc.onion");
		assertEquals(a.username, b.username);
		assertNotEquals("a new process must not share circuits with the old",
				a.password, b.password);
	}

	@Test(timeout = 20_000)
	public void factoriesOfOneProcessShareTheIsolationIdentity()
			throws Exception {
		SocksIsolationSecret secret =
				new SocksIsolationSecret(new SecureRandom());
		Credentials normal = connect(factory(secret), "eeeeeeeeeeeeeeee.onion");
		Credentials fast = connect(factory(secret), "eeeeeeeeeeeeeeee.onion");
		assertEquals(normal.username, fast.username);
		assertEquals("same destination in one process reuses one circuit",
				normal.password, fast.password);
		assertEquals("the secret never leaks through toString",
				"SocksIsolationSecret", secret.toString());
	}

	@Test(timeout = 20_000)
	public void socketsWithoutDestinationAreIsolatedIndividually()
			throws Exception {
		IsolatingSocksSocketFactory f = factory();
		Credentials a = connectLater(f, "dddddddddddddddd.onion");
		Credentials b = connectLater(f, "dddddddddddddddd.onion");
		assertNotEquals(a.username, b.username);
		assertEquals(32, a.username.length());
		assertEquals(32, b.username.length());
	}

	private Credentials connectLater(IsolatingSocksSocketFactory f,
			String host) throws IOException {
		int before = seen.size();
		Socket s = f.createSocket();
		s.connect(InetSocketAddress.createUnresolved(host, 443), 2000);
		s.close();
		long deadline = System.currentTimeMillis() + 5000;
		while (seen.size() <= before && System.currentTimeMillis() < deadline) {
			Thread.yield();
		}
		assertEquals(before + 1, seen.size());
		return seen.get(before);
	}
}
