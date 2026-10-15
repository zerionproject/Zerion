package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

@NotNullByDefault
public final class TorHttp {

	static final int MAX_BODY = 8 * 1024 * 1024;
	static final int MAX_HEADER = 16 * 1024;
	static final int READ_TIMEOUT_MS = 45_000;
	static final int RESPONSE_DEADLINE_MS = 120_000;

	private static final Pattern STATUS_OK =
			Pattern.compile("HTTP/1\\.[01] 200(?: [^\\r\\n]*)?");
	private static final Pattern DIGITS = Pattern.compile("[0-9]{1,10}");
	private static final Pattern TOKEN =
			Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
	private static final ScheduledExecutorService DEADLINES = deadlines();

	private TorHttp() {
	}

	private static ScheduledExecutorService deadlines() {
		ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1,
				r -> {
					Thread t = new Thread(r, "TorHttpDeadline");
					t.setDaemon(true);
					return t;
				});
		e.setRemoveOnCancelPolicy(true);
		return e;
	}

	private static void closeQuietly(Socket socket) {
		try {
			socket.close();
		} catch (IOException ignored) {
		}
	}

	static final class Target {
		final boolean https;
		final String host;
		final int port;
		final String path;

		Target(boolean https, String host, int port, String path) {
			this.https = https;
			this.host = host;
			this.port = port;
			this.path = path;
		}
	}

	@Nullable
	public static String get(String url, int socksPort, String isolationTag) {
		return get(url, socksPort, isolationTag, READ_TIMEOUT_MS,
				RESPONSE_DEADLINE_MS);
	}

	@Nullable
	static String get(String url, int socksPort, String isolationTag,
			int readTimeoutMs, int deadlineMs) {
		if (socksPort <= 0) {
			return null;
		}
		Target target = parseUrl(url);
		if (target == null) {
			return null;
		}
		String host = target.host;
		int port = target.port;

		Socket socket;
		try {
			socket = com.professor.zerion.android.vault.net.TorSockets.open(
					socksPort, 30_000);
		} catch (IOException e) {
			return null;
		}
		try {
			socket.setSoTimeout(readTimeoutMs);
			TorSocks.connect(socket, host, port, "zw-" + isolationTag,
					isolationTag);
			ScheduledFuture<?> deadline = DEADLINES.schedule(
					() -> closeQuietly(socket), deadlineMs,
					TimeUnit.MILLISECONDS);
			try {
				return exchange(socket, target, readTimeoutMs, deadlineMs);
			} finally {
				deadline.cancel(false);
			}
		} catch (Throwable e) {
			return null;
		} finally {
			closeQuietly(socket);
		}
	}

	@Nullable
	private static String exchange(Socket socket, Target target,
			int readTimeoutMs, int deadlineMs) throws IOException {
		String host = target.host;
		Socket stream = socket;
		if (target.https) {
			SSLSocket ssl = (SSLSocket) ((SSLSocketFactory)
					SSLSocketFactory.getDefault())
					.createSocket(socket, host, target.port, true);
			ssl.startHandshake();
			if (!HttpsURLConnection.getDefaultHostnameVerifier()
					.verify(host, ssl.getSession())) {
				return null;
			}
			stream = ssl;
		}
		OutputStream out = stream.getOutputStream();
		String request = "GET " + target.path + " HTTP/1.0\r\n"
				+ "Host: " + host + "\r\n"
				+ "Accept: application/json\r\n"
				+ "Connection: close\r\n\r\n";
		out.write(request.getBytes(StandardCharsets.US_ASCII));
		out.flush();

		return readResponse(stream, MAX_BODY, readTimeoutMs, deadlineMs);
	}

	@Nullable
	static Target parseUrl(String url) {
		URI uri;
		try {
			uri = new URI(url.trim());
		} catch (URISyntaxException e) {
			return null;
		}
		String scheme = uri.getScheme();
		boolean https;
		if ("https".equalsIgnoreCase(scheme)) {
			https = true;
		} else if ("http".equalsIgnoreCase(scheme)) {
			https = false;
		} else {
			return null;
		}
		String host = uri.getHost();
		if (host == null || host.isEmpty() || host.startsWith("[")
				|| uri.getRawUserInfo() != null) {
			return null;
		}
		int port = uri.getPort();
		if (port == -1) {
			port = https ? 443 : 80;
		}
		if (port < 1 || port > 65535) {
			return null;
		}
		String path = uri.getRawPath();
		if (path == null || path.isEmpty()) {
			path = "/";
		}
		String query = uri.getRawQuery();
		if (query != null) {
			path = path + "?" + query;
		}
		return new Target(https, host, port, path);
	}

	static String readResponse(Socket socket, int maxBody, int readTimeoutMs,
			int deadlineMs) throws IOException {
		return readResponse(new BufferedInputStream(new DeadlineInputStream(
				socket, readTimeoutMs, deadlineMs)), maxBody);
	}

	static String readResponse(InputStream in, int maxBody)
			throws IOException {
		String[] lines = readHeader(in).split("\r\n", -1);
		if (!STATUS_OK.matcher(lines[0]).matches()) {
			throw new IOException("unexpected status");
		}
		long contentLength = -1;
		for (int i = 1; i < lines.length; i++) {
			String line = lines[i];
			int colon = line.indexOf(':');
			if (colon <= 0 || !TOKEN.matcher(line.substring(0, colon))
					.matches()) {
				throw new IOException("malformed header");
			}
			String name = line.substring(0, colon).toLowerCase(Locale.US);
			String value = line.substring(colon + 1).trim();
			if (name.equals("transfer-encoding")) {
				throw new IOException("unsupported transfer encoding");
			}
			if (name.equals("content-encoding")
					&& !value.equalsIgnoreCase("identity")) {
				throw new IOException("unsupported content encoding");
			}
			if (name.equals("content-length")) {
				if (!DIGITS.matcher(value).matches()) {
					throw new IOException("malformed content length");
				}
				long length = Long.parseLong(value);
				if (contentLength >= 0 && contentLength != length) {
					throw new IOException("conflicting content length");
				}
				contentLength = length;
			}
		}
		if (contentLength > maxBody) {
			throw new IOException("body too large");
		}
		byte[] body;
		if (contentLength >= 0) {
			body = readExactly(in, (int) contentLength);
			if (in.read() != -1) {
				throw new IOException("data after the body");
			}
		} else {
			body = readToEnd(in, maxBody);
		}
		return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT)
				.decode(ByteBuffer.wrap(body)).toString();
	}

	private static String readHeader(InputStream in) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		int matched = 0;
		while (matched < 4) {
			int b = in.read();
			if (b < 0) {
				throw new IOException("incomplete header");
			}
			if (buf.size() >= MAX_HEADER) {
				throw new IOException("header too large");
			}
			buf.write(b);
			if (b == ((matched % 2 == 0) ? '\r' : '\n')) {
				matched++;
			} else {
				matched = b == '\r' ? 1 : 0;
			}
		}
		byte[] head = buf.toByteArray();
		return new String(head, 0, head.length - 4,
				StandardCharsets.ISO_8859_1);
	}

	private static byte[] readExactly(InputStream in, int length)
			throws IOException {
		byte[] body = new byte[length];
		int off = 0;
		while (off < length) {
			int r = in.read(body, off, length - off);
			if (r < 0) {
				throw new IOException("truncated body");
			}
			off += r;
		}
		return body;
	}

	private static byte[] readToEnd(InputStream in, int maxBody)
			throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] tmp = new byte[8192];
		int r;
		while ((r = in.read(tmp)) >= 0) {
			if (buf.size() + r > maxBody) {
				throw new IOException("body too large");
			}
			buf.write(tmp, 0, r);
		}
		return buf.toByteArray();
	}

	private static final class DeadlineInputStream extends InputStream {

		private final Socket socket;
		private final InputStream in;
		private final int readTimeoutMs;
		private final long deadlineNanos;

		DeadlineInputStream(Socket socket, int readTimeoutMs, int deadlineMs)
				throws IOException {
			this.socket = socket;
			this.in = socket.getInputStream();
			this.readTimeoutMs = readTimeoutMs;
			this.deadlineNanos = System.nanoTime() + deadlineMs * 1_000_000L;
		}

		private void arm() throws IOException {
			long left = (deadlineNanos - System.nanoTime()) / 1_000_000L;
			if (left <= 0) {
				throw new SocketTimeoutException("response deadline passed");
			}
			socket.setSoTimeout((int) Math.min(readTimeoutMs, left));
		}

		@Override
		public int read() throws IOException {
			arm();
			return in.read();
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			arm();
			return in.read(b, off, len);
		}

		@Override
		public void close() throws IOException {
			in.close();
		}
	}
}
