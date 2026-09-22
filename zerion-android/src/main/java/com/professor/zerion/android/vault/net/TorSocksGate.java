package com.professor.zerion.android.vault.net;

import org.zerionproject.core.socks.TorSocksConnector;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.function.Predicate;

/**
 * A loopback SOCKS5 relay for the one client that cannot open a Unix domain
 * socket: the native Monero wallet, which speaks TCP to its proxy. Tor's
 * own SOCKS listener is a Unix domain socket no other process can open, and
 * this relay is the only TCP way to it. Unlike Tor it verifies the SOCKS
 * password: a client must present the process secret the native wallet is
 * given, or the connection is refused before anything reaches Tor. The
 * username is forwarded unchanged so Tor still isolates the wallet's
 * streams by it. The relay speaks only the first two SOCKS5 exchanges
 * itself (method selection and username/password); everything after,
 * including the connect request, is carried through to Tor verbatim.
 */
@NotNullByDefault
public final class TorSocksGate {

	static final int MAX_CONNECTIONS = 16;
	private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
	private static final int UPSTREAM_CONNECT_TIMEOUT_MS = 10_000;
	private static final int BUFFER_BYTES = 16 * 1024;

	private final TorSocksConnector upstream;
	private final Predicate<String> passwordAccepted;
	private final String processSecret = randomHex();
	private final ServerSocket server;
	private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r, "tor-socks-gate");
		t.setDaemon(true);
		return t;
	});
	private final Semaphore connections = new Semaphore(MAX_CONNECTIONS);

	/**
	 * @param passwordAccepted accepts the passwords of in-process clients
	 * that hold their own per-process secret, such as the native wallet;
	 * the gate's own {@link #processSecret()} is always accepted too.
	 */
	public TorSocksGate(TorSocksConnector upstream,
			Predicate<String> passwordAccepted) throws IOException {
		this.upstream = upstream;
		this.passwordAccepted = p -> constantTimeEquals(processSecret, p)
				|| passwordAccepted.test(p);
		server = new ServerSocket(0, MAX_CONNECTIONS,
				InetAddress.getLoopbackAddress());
		pool.execute(this::acceptLoop);
	}

	public int port() {
		return server.getLocalPort();
	}

	/**
	 * A per-process password the gate accepts, for in-process clients that
	 * can only be handed a proxy address and credentials. Never persisted.
	 */
	public String processSecret() {
		return processSecret;
	}

	private static String randomHex() {
		byte[] b = new byte[16];
		new java.security.SecureRandom().nextBytes(b);
		StringBuilder sb = new StringBuilder(32);
		for (byte x : b) {
			sb.append(Character.forDigit((x >> 4) & 0xF, 16));
			sb.append(Character.forDigit(x & 0xF, 16));
		}
		return sb.toString();
	}

	public void close() {
		try {
			server.close();
		} catch (IOException ignored) {
		}
		pool.shutdownNow();
	}

	private void acceptLoop() {
		while (!server.isClosed()) {
			Socket client;
			try {
				client = server.accept();
			} catch (IOException e) {
				return;
			}
			if (!connections.tryAcquire()) {
				closeQuietly(client);
				continue;
			}
			try {
				pool.execute(() -> serve(client));
			} catch (RuntimeException e) {
				connections.release();
				closeQuietly(client);
			}
		}
	}

	private void serve(Socket client) {
		Socket tor = null;
		try {
			client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
			InputStream in = client.getInputStream();
			OutputStream out = client.getOutputStream();
			byte[] greeting = readFully(in, 2);
			if (greeting[0] != 5) return;
			int methodCount = greeting[1] & 0xFF;
			byte[] methods = readFully(in, methodCount);
			boolean userPass = false;
			for (byte m : methods) if (m == 2) userPass = true;
			if (!userPass) {
				out.write(new byte[] {5, (byte) 0xFF});
				out.flush();
				return;
			}
			out.write(new byte[] {5, 2});
			out.flush();
			byte[] authVersion = readFully(in, 1);
			if (authVersion[0] != 1) return;
			byte[] user = readFully(in, readFully(in, 1)[0] & 0xFF);
			byte[] pass = readFully(in, readFully(in, 1)[0] & 0xFF);
			if (!passwordAccepted.test(
					new String(pass, StandardCharsets.UTF_8))) {
				out.write(new byte[] {1, 1});
				out.flush();
				return;
			}
			tor = upstream.openProxySocket(UPSTREAM_CONNECT_TIMEOUT_MS);
			tor.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
			OutputStream torOut = tor.getOutputStream();
			InputStream torIn = tor.getInputStream();
			torOut.write(new byte[] {5, 1, 2});
			torOut.flush();
			byte[] torMethod = readFully(torIn, 2);
			if (torMethod[0] != 5 || torMethod[1] != 2) return;
			byte[] auth = new byte[3 + user.length + pass.length];
			auth[0] = 1;
			auth[1] = (byte) user.length;
			System.arraycopy(user, 0, auth, 2, user.length);
			auth[2 + user.length] = (byte) pass.length;
			System.arraycopy(pass, 0, auth, 3 + user.length, pass.length);
			torOut.write(auth);
			torOut.flush();
			byte[] torAuth = readFully(torIn, 2);
			if (torAuth[0] != 1 || torAuth[1] != 0) return;
			out.write(new byte[] {1, 0});
			out.flush();
			client.setSoTimeout(0);
			tor.setSoTimeout(0);
			Socket torFinal = tor;
			pool.execute(() -> pump(torFinal, client));
			pump(client, torFinal);
		} catch (IOException ignored) {
		} finally {
			closeQuietly(client);
			if (tor != null) closeQuietly(tor);
			connections.release();
		}
	}

	private static void pump(Socket from, Socket to) {
		byte[] buf = new byte[BUFFER_BYTES];
		try {
			InputStream in = from.getInputStream();
			OutputStream out = to.getOutputStream();
			int n;
			while ((n = in.read(buf)) >= 0) {
				out.write(buf, 0, n);
				out.flush();
			}
			to.shutdownOutput();
		} catch (IOException ignored) {
		} finally {
			closeQuietly(from);
			closeQuietly(to);
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

	private static void closeQuietly(Socket s) {
		try {
			s.close();
		} catch (IOException ignored) {
		}
	}

	/** Constant-time comparison for the password check. */
	public static boolean constantTimeEquals(String a, String b) {
		byte[] x = a.getBytes(StandardCharsets.UTF_8);
		byte[] y = b.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(x, y);
	}
}
