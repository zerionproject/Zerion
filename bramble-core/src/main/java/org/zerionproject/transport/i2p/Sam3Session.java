package org.zerionproject.transport.i2p;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

/**
 * A SAM v3 client session against a local I2P router bridge (default
 * {@code 127.0.0.1:7656}). The control socket runs HELLO + SESSION CREATE and
 * stays open for the session's life; {@link #connect} dials a peer destination
 * on its own socket, and {@link #forwardTo} asks the router to forward inbound
 * I2P streams to a local port so the transport can accept them with an ordinary
 * {@code ServerSocket}, exactly as the Tor hidden service does.
 *
 * <p>This class speaks the wire protocol only; it does not run the router,
 * bootstrap the network, or manage a poller. It is fully exercisable against a
 * fake bridge over loopback.
 */
@NotNullByDefault
public class Sam3Session {

	private static final String SAM_VERSION = "HELLO VERSION MIN=3.1 MAX=3.1";
	private static final int MAX_B64_LEN = 8192;
	private static final int MAX_REPLY_LEN = 8192;

	private final String host;
	private final int port;
	private final int connectTimeoutMs;
	private final String sessionId;
	private final Socket controlSocket;
	private final String localDestination;
	private final String privateKey;

	private Sam3Session(String host, int port, int connectTimeoutMs,
			String sessionId, Socket controlSocket, String localDestination,
			String privateKey) {
		this.host = host;
		this.port = port;
		this.connectTimeoutMs = connectTimeoutMs;
		this.sessionId = sessionId;
		this.controlSocket = controlSocket;
		this.localDestination = localDestination;
		this.privateKey = privateKey;
	}

	/**
	 * Opens a control socket and creates a STREAM session. When
	 * {@code privateKey} is null a fresh transient destination is generated;
	 * otherwise the persisted key recreates the same destination. Returns a
	 * live session whose {@link #getPrivateKey} should be persisted on first
	 * run and {@link #getLocalDestination} published for peers to dial.
	 */
	public static Sam3Session open(String host, int port, int connectTimeoutMs,
			String sessionId, @Nullable String privateKey) throws IOException {
		if (privateKey != null) checkB64(privateKey);
		Socket control = new Socket();
		try {
			control.connect(new InetSocketAddress(host, port),
					connectTimeoutMs);
			control.setSoTimeout(connectTimeoutMs);
			OutputStream out = control.getOutputStream();
			InputStream in = control.getInputStream();
			sendLine(out, SAM_VERSION);
			requireOk(readReply(in));
			String dest = privateKey == null ? "TRANSIENT" : privateKey;
			sendLine(out, "SESSION CREATE STYLE=STREAM ID=" + sessionId
					+ " DESTINATION=" + dest
					+ " SIGNATURE_TYPE=7 i2cp.leaseSetEncType=6,4");
			Sam3Reply status = readReply(in);
			requireOk(status);
			String createdKey = status.get("DESTINATION");
			if (createdKey == null) {
				throw new Sam3Exception("I2P_ERROR",
						"SESSION STATUS without DESTINATION");
			}
			checkB64(createdKey);
			sendLine(out, "NAMING LOOKUP NAME=ME");
			Sam3Reply naming = readReply(in);
			requireOk(naming);
			String publicDest = naming.get("VALUE");
			if (publicDest == null) {
				throw new Sam3Exception("I2P_ERROR",
						"NAMING REPLY without VALUE");
			}
			checkB64(publicDest);
			return new Sam3Session(host, port, connectTimeoutMs, sessionId,
					control, publicDest, createdKey);
		} catch (IOException | RuntimeException e) {
			closeQuietly(control);
			throw e;
		}
	}

	/**
	 * Dials {@code peerDestination} (a base64 destination) and returns a
	 * connected socket whose streams carry the raw peer connection. Throws
	 * {@link Sam3Exception} carrying the SAM result on failure (for example
	 * {@code CANT_REACH_PEER}).
	 */
	public Socket connect(String peerDestination) throws IOException {
		checkB64(peerDestination);
		Socket s = new Socket();
		try {
			s.connect(new InetSocketAddress(host, port), connectTimeoutMs);
			s.setSoTimeout(connectTimeoutMs);
			OutputStream out = s.getOutputStream();
			InputStream in = s.getInputStream();
			sendLine(out, SAM_VERSION);
			requireOk(readReply(in));
			sendLine(out, "STREAM CONNECT ID=" + sessionId + " DESTINATION="
					+ peerDestination + " SILENT=false");
			requireOk(readReply(in));
			return s;
		} catch (IOException | RuntimeException e) {
			closeQuietly(s);
			throw e;
		}
	}

	/**
	 * Registers forwarding of inbound I2P streams to {@code 127.0.0.1:localPort}
	 * with no per-stream header, so the transport accepts them as plain
	 * sockets. Sent on the control socket and active for the session's life.
	 */
	public void forwardTo(int localPort) throws IOException {
		OutputStream out = controlSocket.getOutputStream();
		InputStream in = controlSocket.getInputStream();
		sendLine(out, "STREAM FORWARD ID=" + sessionId + " PORT=" + localPort
				+ " SILENT=true");
		requireOk(readReply(in));
	}

	public String getLocalDestination() {
		return localDestination;
	}

	public String getPrivateKey() {
		return privateKey;
	}

	public void close() {
		closeQuietly(controlSocket);
	}

	private static void requireOk(Sam3Reply reply) throws Sam3Exception {
		if (!reply.isOk()) {
			throw new Sam3Exception(reply.getResult(), reply.get("MESSAGE"));
		}
	}

	/**
	 * Rejects any value that is not a plain I2P base64 blob before it is
	 * concatenated into a newline-delimited SAM command. This is the load-
	 * bearing defence against command injection through a peer-controlled
	 * destination or a hostile bridge reply: a space or newline in the value
	 * would otherwise append SAM parameters or a whole second command.
	 */
	private static void checkB64(String value) throws Sam3Exception {
		int n = value.length();
		if (n == 0 || n > MAX_B64_LEN) {
			throw new Sam3Exception("INVALID_KEY", "bad value length");
		}
		for (int i = 0; i < n; i++) {
			char c = value.charAt(i);
			boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
					|| (c >= '0' && c <= '9') || c == '~' || c == '-'
					|| c == '=';
			if (!ok) {
				throw new Sam3Exception("INVALID_KEY", "bad value char");
			}
		}
	}

	private static void sendLine(OutputStream out, String line)
			throws IOException {
		out.write((line + "\n").getBytes(StandardCharsets.US_ASCII));
		out.flush();
	}

	/** Reads a single newline-terminated reply without consuming any bytes
	 * that follow it, so a raw stream after {@code RESULT=OK} stays intact. */
	private static Sam3Reply readReply(InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		while (true) {
			int b = in.read();
			if (b == -1) {
				if (sb.length() == 0) throw new EOFException();
				break;
			}
			if (b == '\n') break;
			if (b != '\r') {
				if (sb.length() >= MAX_REPLY_LEN) {
					throw new IOException("SAM reply too long");
				}
				sb.append((char) b);
			}
		}
		return Sam3Reply.parse(sb.toString());
	}

	private static void closeQuietly(Socket s) {
		try {
			s.close();
		} catch (IOException ignored) {
		}
	}
}
