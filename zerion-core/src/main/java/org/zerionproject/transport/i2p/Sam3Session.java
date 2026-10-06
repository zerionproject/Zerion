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
