package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

@NotNullByDefault
public final class TorHttp {

	private static final int MAX_BODY = 8 * 1024 * 1024;

	private TorHttp() {
	}

	@Nullable
	public static String get(String url, int socksPort, String isolationTag) {
		if (socksPort <= 0) {
			return null;
		}
		String u = url.trim();
		boolean https = u.startsWith("https://");
		String rest;
		if (https) {
			rest = u.substring("https://".length());
		} else if (u.startsWith("http://")) {
			rest = u.substring("http://".length());
		} else {
			https = false;
			rest = u;
		}
		int slash = rest.indexOf('/');
		String hostPort = slash < 0 ? rest : rest.substring(0, slash);
		String path = slash < 0 ? "/" : rest.substring(slash);
		String host;
		int port;
		int colon = hostPort.indexOf(':');
		if (colon < 0) {
			host = hostPort;
			port = https ? 443 : 80;
		} else {
			host = hostPort.substring(0, colon);
			try {
				port = Integer.parseInt(hostPort.substring(colon + 1));
			} catch (NumberFormatException e) {
				return null;
			}
		}

		Socket socket = new Socket();
		try {
			socket.connect(new InetSocketAddress("127.0.0.1", socksPort),
					30_000);
			socket.setSoTimeout(45_000);
			TorSocks.connect(socket, host, port, "zw-" + isolationTag,
					isolationTag);
			Socket stream = socket;
			if (https) {
				SSLSocket ssl = (SSLSocket) ((SSLSocketFactory)
						SSLSocketFactory.getDefault())
						.createSocket(socket, host, port, true);
				ssl.startHandshake();
				if (!HttpsURLConnection.getDefaultHostnameVerifier()
						.verify(host, ssl.getSession())) {
					return null;
				}
				stream = ssl;
			}
			OutputStream out = stream.getOutputStream();
			String request = "GET " + path + " HTTP/1.0\r\n"
					+ "Host: " + host + "\r\n"
					+ "Accept: application/json\r\n"
					+ "Connection: close\r\n\r\n";
			out.write(request.getBytes(StandardCharsets.US_ASCII));
			out.flush();

			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			InputStream in = stream.getInputStream();
			byte[] tmp = new byte[8192];
			int r;
			while ((r = in.read(tmp)) >= 0) {
				buf.write(tmp, 0, r);
				if (buf.size() > MAX_BODY) {
					break;
				}
			}
			String resp = new String(buf.toByteArray(),
					StandardCharsets.UTF_8);
			int statusEnd = resp.indexOf("\r\n");
			if (statusEnd > 0) {
				String status = resp.substring(0, statusEnd);
				if (!status.contains(" 200")) {
					return null;
				}
			}
			int sep = resp.indexOf("\r\n\r\n");
			return sep >= 0 ? resp.substring(sep + 4) : resp;
		} catch (Throwable e) {
			return null;
		} finally {
			try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
	}
}
