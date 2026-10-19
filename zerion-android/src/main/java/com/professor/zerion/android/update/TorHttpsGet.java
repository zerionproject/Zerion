package com.professor.zerion.android.update;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.LongSupplier;

import javax.net.SocketFactory;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

@NotNullByDefault
final class TorHttpsGet {

	static final int CONNECT_TIMEOUT_MS = 60_000;
	static final int READ_TIMEOUT_MS = 60_000;
	static final int MAX_HEADER_BYTES = 16 * 1024;
	static final long NO_DEADLINE = Long.MAX_VALUE;

	interface Watchdog {
		Runnable arm(Runnable atDeadline, long delayMs);
	}

	static final Watchdog CLOSER = (atDeadline, delayMs) -> {
		ScheduledFuture<?> f = Deadlines.EXECUTOR.schedule(atDeadline,
				delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
		return () -> f.cancel(false);
	};

	private static final class Deadlines {
		static final ScheduledThreadPoolExecutor EXECUTOR = executor();

		private static ScheduledThreadPoolExecutor executor() {
			ScheduledThreadPoolExecutor e = new ScheduledThreadPoolExecutor(1,
					r -> {
						Thread t = new Thread(r, "https-deadline");
						t.setDaemon(true);
						return t;
					});
			e.setRemoveOnCancelPolicy(true);
			return e;
		}
	}

	static final class Response {
		final int status;
		final byte[] body;

		Response(int status, byte[] body) {
			this.status = status;
			this.body = body;
		}
	}

	private TorHttpsGet() {
	}

	static Response get(SocketFactory torSockets, String url, int maxBody)
			throws IOException {
		return get(torSockets, url, maxBody, NO_DEADLINE,
				System::currentTimeMillis);
	}

	static Response get(SocketFactory torSockets, String url, int maxBody,
			long deadlineMs, LongSupplier clock) throws IOException {
		return get(torSockets, url, maxBody, deadlineMs, clock, CLOSER);
	}

	static Response get(SocketFactory torSockets, String url, int maxBody,
			long deadlineMs, LongSupplier clock, Watchdog watchdog)
			throws IOException {
		URI uri = URI.create(url);
		if (!"https".equals(uri.getScheme())) throw new IOException("not https");
		String host = uri.getHost();
		if (host == null || host.isEmpty()) throw new IOException("no host");
		int port = uri.getPort() == -1 ? 443 : uri.getPort();
		String path = uri.getRawPath();
		if (path == null || path.isEmpty()) path = "/";
		int connectTimeout = remaining(CONNECT_TIMEOUT_MS, deadlineMs, clock);
		Socket raw = torSockets.createSocket();
		Runnable disarm = deadlineMs == NO_DEADLINE ? null
				: watchdog.arm(() -> closeQuietly(raw),
				remaining(Integer.MAX_VALUE, deadlineMs, clock));
		try {
			raw.connect(InetSocketAddress.createUnresolved(host, port),
					connectTimeout);
			raw.setSoTimeout(remaining(READ_TIMEOUT_MS, deadlineMs, clock));
			SSLSocket ssl = (SSLSocket) ((SSLSocketFactory)
					SSLSocketFactory.getDefault()).createSocket(raw, host, port,
					true);
			ssl.setSoTimeout(remaining(READ_TIMEOUT_MS, deadlineMs, clock));
			ssl.startHandshake();
			if (!HttpsURLConnection.getDefaultHostnameVerifier()
					.verify(host, ssl.getSession())) {
				throw new IOException("certificate does not match the host");
			}
			OutputStream out = ssl.getOutputStream();
			String request = "GET " + path + " HTTP/1.0\r\n"
					+ "Host: " + host + "\r\n"
					+ "Accept-Encoding: identity\r\n"
					+ "Connection: close\r\n\r\n";
			out.write(request.getBytes(StandardCharsets.US_ASCII));
			out.flush();
			return parse(readAll(ssl, ssl.getInputStream(),
					maxBody + MAX_HEADER_BYTES, deadlineMs, clock), maxBody);
		} finally {
			if (disarm != null) disarm.run();
			closeQuietly(raw);
		}
	}

	private static void closeQuietly(Socket s) {
		try {
			s.close();
		} catch (IOException ignored) {
		}
	}

	static int remaining(int timeoutMs, long deadlineMs, LongSupplier clock)
			throws IOException {
		if (deadlineMs == NO_DEADLINE) return timeoutMs;
		long left = deadlineMs - clock.getAsLong();
		if (left <= 0) throw new IOException("deadline passed");
		return (int) Math.min(timeoutMs, left);
	}

	static byte[] readAll(Socket socket, InputStream in, int limit,
			long deadlineMs, LongSupplier clock) throws IOException {
		if (deadlineMs == NO_DEADLINE) return readAll(in, limit);
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] tmp = new byte[8192];
		while (true) {
			socket.setSoTimeout(remaining(READ_TIMEOUT_MS, deadlineMs, clock));
			int r = in.read(tmp);
			if (r < 0) break;
			buf.write(tmp, 0, r);
			if (buf.size() > limit) throw new IOException("response too large");
		}
		return buf.toByteArray();
	}

	static byte[] readAll(InputStream in, int limit) throws IOException {
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] tmp = new byte[8192];
		int r;
		while ((r = in.read(tmp)) >= 0) {
			buf.write(tmp, 0, r);
			if (buf.size() > limit) throw new IOException("response too large");
		}
		return buf.toByteArray();
	}

	static Response parse(byte[] response, int maxBody) throws IOException {
		int headerEnd = indexOf(response, new byte[] {'\r', '\n', '\r', '\n'});
		if (headerEnd < 0 || headerEnd > MAX_HEADER_BYTES) {
			throw new IOException("malformed response");
		}
		String head = new String(response, 0, headerEnd,
				StandardCharsets.ISO_8859_1);
		String statusLine = head.split("\r\n", 2)[0];
		String[] parts = statusLine.split(" ");
		if (parts.length < 2 || !parts[0].startsWith("HTTP/1.")) {
			throw new IOException("malformed status line");
		}
		int status;
		try {
			status = Integer.parseInt(parts[1]);
		} catch (NumberFormatException e) {
			throw new IOException("malformed status code");
		}
		for (String line : head.split("\r\n")) {
			String l = line.toLowerCase(java.util.Locale.US);
			if (l.startsWith("transfer-encoding:") && !l.contains("identity")) {
				throw new IOException("unsupported transfer encoding");
			}
			if (l.startsWith("content-encoding:") && !l.contains("identity")) {
				throw new IOException("unsupported content encoding");
			}
		}
		byte[] body = Arrays.copyOfRange(response, headerEnd + 4,
				response.length);
		if (body.length > maxBody) throw new IOException("body too large");
		return new Response(status, body);
	}

	private static int indexOf(byte[] data, byte[] pattern) {
		outer:
		for (int i = 0; i + pattern.length <= data.length; i++) {
			for (int j = 0; j < pattern.length; j++) {
				if (data[i + j] != pattern[j]) continue outer;
			}
			return i;
		}
		return -1;
	}
}
