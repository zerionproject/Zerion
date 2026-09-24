package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

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
import java.util.List;

/**
 * The pieces of the Tor control protocol the transport speaks itself: the
 * cookie file, a loopback connection that sends one command and collects
 * every reply line, and the reply check. Shared by the privacy configurator
 * and the onion service controller.
 */
@NotNullByDefault
final class TorControl {

	static final String COOKIE_FILE = ".tor/control_auth_cookie";
	static final int COOKIE_BYTES = 32;
	static final int CONNECT_TIMEOUT_MS = 5_000;
	static final int READ_TIMEOUT_MS = 15_000;

	interface Connection extends Closeable {

		/** Sends one command and returns every reply line, in order. */
		List<String> send(String command) throws IOException;
	}

	interface ConnectionFactory {

		Connection open() throws IOException;
	}

	private TorControl() {
	}

	static byte[] readCookie(File torDirectory) throws IOException {
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

	static void requireOk(List<String> reply, String what)
			throws IOException {
		if (reply.isEmpty() || !reply.get(reply.size() - 1).startsWith("250")) {
			throw new IOException("Tor control " + what + " failed");
		}
	}

	static void quit(Connection c) {
		try {
			c.send("QUIT");
		} catch (IOException ignored) {
		}
	}

	/** Plain-text Tor control protocol over a loopback socket. */
	static final class SocketConnection implements Connection {

		private final Socket socket;
		private final BufferedReader in;
		private final Writer out;

		SocketConnection(int controlPort) throws IOException {
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
