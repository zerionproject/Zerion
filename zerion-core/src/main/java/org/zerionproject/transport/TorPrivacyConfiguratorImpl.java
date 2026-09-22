package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.plugin.TorControlPort;
import org.zerionproject.core.api.plugin.TorDirectory;
import org.zerionproject.core.api.plugin.TorSocksPath;
import org.zerionproject.core.util.StringUtils;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;

/**
 * Talks to the Tor control port over its own cookie-authenticated
 * connection, moves the SOCKS listener from the loopback TCP port the
 * shipped configuration opens to a Unix domain socket inside the app's
 * private directory, sets the listener's isolation flags and connection
 * padding with SETCONF, then reads the effective configuration back with
 * GETCONF and refuses to accept anything but that single listener carrying
 * every required isolation flag, no TCP listener left beside it, and
 * padding switched on. The check is made against what Tor reports, not
 * against what was requested. With the listener on a Unix socket no other
 * process on the device can use, probe or hijack this app's Tor client.
 */
@NotNullByDefault
public class TorPrivacyConfiguratorImpl implements TorPrivacyConfigurator {

	/**
	 * IsolateSOCKSAuth makes the SOCKS credentials part of Tor's circuit
	 * isolation key, which is what turns the per-destination usernames of
	 * the socket factory into per-destination circuits; the other two keep
	 * client-address and destination isolation on regardless of credentials.
	 */
	static final String[] REQUIRED_SOCKS_FLAGS = {
			"IsolateSOCKSAuth", "IsolateClientAddr", "IsolateDestAddr"
	};

	private static final String COOKIE_FILE = ".tor/control_auth_cookie";
	private static final int COOKIE_BYTES = 32;
	private static final int CONNECT_TIMEOUT_MS = 5_000;
	private static final int READ_TIMEOUT_MS = 15_000;

	interface ControlConnection extends Closeable {

		/** Sends one command and returns every reply line, in order. */
		List<String> send(String command) throws IOException;
	}

	interface ControlConnectionFactory {

		ControlConnection open() throws IOException;
	}

	private final File torDirectory;
	private final String listener;
	private final ControlConnectionFactory connectionFactory;

	@Inject
	public TorPrivacyConfiguratorImpl(@TorDirectory File torDirectory,
			@TorSocksPath File socksPath, @TorControlPort int controlPort) {
		this(torDirectory, socksPath,
				() -> new SocketControlConnection(controlPort));
	}

	TorPrivacyConfiguratorImpl(File torDirectory, File socksPath,
			ControlConnectionFactory connectionFactory) {
		this.torDirectory = torDirectory;
		this.listener = listenerFor(socksPath);
		this.connectionFactory = connectionFactory;
	}

	/** Tor's spelling of a Unix domain socket listener. */
	static String listenerFor(File socksPath) {
		String path = socksPath.getAbsolutePath();
		if (path.indexOf(' ') >= 0 || path.indexOf('"') >= 0) {
			throw new IllegalArgumentException("socket path");
		}
		return "unix:" + path;
	}

	@Override
	public void applyAndVerify() throws IOException {
		byte[] cookie = readCookie();
		try (ControlConnection c = connectionFactory.open()) {
			requireOk(c.send("AUTHENTICATE "
					+ StringUtils.toHexString(cookie)), "authentication");
			requireOk(c.send("SETCONF SocksPort=\"" + listener + " "
					+ String.join(" ", REQUIRED_SOCKS_FLAGS)
					+ "\" ConnectionPadding=1"), "configuration");
			List<String> socks = c.send("GETCONF SocksPort");
			if (!socksIsolationActive(socks, listener)) {
				throw new IOException("Tor SOCKS isolation is not active");
			}
			if (otherSocksListener(socks, listener)) {
				throw new IOException("Tor keeps another SOCKS listener");
			}
			if (!paddingActive(c.send("GETCONF ConnectionPadding"))) {
				throw new IOException("Tor connection padding is not active");
			}
			try {
				c.send("QUIT");
			} catch (IOException ignored) {
			}
		} finally {
			Arrays.fill(cookie, (byte) 0);
		}
	}

	private byte[] readCookie() throws IOException {
		File f = new File(torDirectory, COOKIE_FILE);
		if (!f.isFile()) throw new IOException("Tor control cookie missing");
		byte[] cookie = new byte[COOKIE_BYTES];
		try (InputStream in = new FileInputStream(f)) {
			int off = 0;
			while (off < COOKIE_BYTES) {
				int r = in.read(cookie, off, COOKIE_BYTES - off);
				if (r < 0) throw new IOException("Tor control cookie short");
				off += r;
			}
		}
		return cookie;
	}

	private static void requireOk(List<String> reply, String what)
			throws IOException {
		if (reply.isEmpty() || !reply.get(reply.size() - 1).startsWith("250")) {
			throw new IOException("Tor control " + what + " failed");
		}
	}

	/**
	 * True only if Tor reports a SocksPort line for our listener that
	 * carries every required flag and no flag that negates one of them.
	 */
	static boolean socksIsolationActive(List<String> reply, String listener) {
		for (String line : reply) {
			String[] tokens = socksTokens(line);
			if (tokens == null || !sameListener(tokens[0], listener)) continue;
			boolean allPresent = true;
			for (String required : REQUIRED_SOCKS_FLAGS) {
				boolean present = false;
				for (int i = 1; i < tokens.length; i++) {
					if (tokens[i].equalsIgnoreCase("No" + required)) {
						return false;
					}
					if (tokens[i].equalsIgnoreCase(required)) present = true;
				}
				if (!present) allPresent = false;
			}
			if (allPresent) return true;
		}
		return false;
	}

	/**
	 * True if Tor reports any SocksPort line for a listener other than ours,
	 * such as the loopback TCP port of the shipped configuration.
	 */
	static boolean otherSocksListener(List<String> reply, String listener) {
		for (String line : reply) {
			String[] tokens = socksTokens(line);
			if (tokens == null) continue;
			if (!sameListener(tokens[0], listener)) return true;
		}
		return false;
	}

	@javax.annotation.Nullable
	private static String[] socksTokens(String line) {
		String body = stripStatus(line);
		if (!body.startsWith("SocksPort=")) return null;
		String value = body.substring("SocksPort=".length()).trim();
		if (value.isEmpty()) return null;
		return value.split("\\s+");
	}

	private static boolean sameListener(String reported, String listener) {
		String r = reported;
		if (r.startsWith("unix:\"") && r.endsWith("\"")) {
			r = "unix:" + r.substring(6, r.length() - 1);
		}
		return r.equals(listener);
	}

	static boolean paddingActive(List<String> reply) {
		for (String line : reply) {
			if (stripStatus(line).equals("ConnectionPadding=1")) return true;
		}
		return false;
	}

	private static String stripStatus(String line) {
		if (line.length() >= 4 && Character.isDigit(line.charAt(0))
				&& Character.isDigit(line.charAt(1))
				&& Character.isDigit(line.charAt(2))) {
			return line.substring(4).trim();
		}
		return line.trim();
	}

	/** Plain-text Tor control protocol over a loopback socket. */
	private static final class SocketControlConnection
			implements ControlConnection {

		private final Socket socket;
		private final BufferedReader in;
		private final Writer out;

		SocketControlConnection(int controlPort) throws IOException {
			socket = new Socket();
			socket.connect(new InetSocketAddress("127.0.0.1", controlPort),
					CONNECT_TIMEOUT_MS);
			socket.setSoTimeout(READ_TIMEOUT_MS);
			in = new BufferedReader(new InputStreamReader(
					socket.getInputStream(), StandardCharsets.US_ASCII));
			out = new OutputStreamWriter(socket.getOutputStream(),
					StandardCharsets.US_ASCII);
		}

		@Override
		public List<String> send(String command) throws IOException {
			out.write(command);
			out.write("\r\n");
			out.flush();
			List<String> lines = new ArrayList<>();
			while (true) {
				String line = in.readLine();
				if (line == null) throw new IOException("control closed");
				lines.add(line);
				if (line.length() < 4) throw new IOException("bad reply");
				char sep = line.charAt(3);
				if (sep == '+') {
					String data;
					while ((data = in.readLine()) != null && !data.equals(".")) {
						lines.add(data);
					}
					if (data == null) throw new IOException("control closed");
				} else if (sep == ' ') {
					return lines;
				}
			}
		}

		@Override
		public void close() throws IOException {
			socket.close();
		}
	}
}
