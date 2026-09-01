package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@NotNullByDefault
public final class BtcPrice {

	private static final String HOST =
			"mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion";
	private static final int PORT = 80;

	public static final String[] CURRENCIES =
			{"EUR", "USD", "GBP", "CAD", "CHF", "AUD", "JPY"};

	public static final class Rates {
		public final double eur;
		public final double usd;
		private final java.util.Map<String, Double> map;

		Rates(java.util.Map<String, Double> map) {
			this.map = map;
			Double e = map.get("EUR");
			Double u = map.get("USD");
			this.eur = e == null ? 0 : e;
			this.usd = u == null ? 0 : u;
		}

		public double get(String currency) {
			Double d = map.get(currency);
			return d == null ? 0 : d;
		}

		public boolean has(String currency) {
			Double d = map.get(currency);
			return d != null && d > 0;
		}

		public String toJson() {
			StringBuilder sb = new StringBuilder("{");
			boolean first = true;
			for (java.util.Map.Entry<String, Double> e : map.entrySet()) {
				if (!first) {
					sb.append(',');
				}
				sb.append('"').append(e.getKey()).append("\":")
						.append(e.getValue());
				first = false;
			}
			return sb.append('}').toString();
		}

		public static Rates fromJson(String json) {
			java.util.Map<String, Double> m = new java.util.HashMap<>();
			for (String c : CURRENCIES) {
				double v = parseValue(json, c);
				if (v > 0) {
					m.put(c, v);
				}
			}
			return new Rates(m);
		}

		public boolean isEmpty() {
			return map.isEmpty();
		}
	}

	private BtcPrice() {
	}

	public static Rates fetch(int socksPort, String isolationTag)
			throws IOException {
		if (socksPort <= 0) {
			throw new IOException("Tor is not ready");
		}
		Socket socket = new Socket();
		try {
			socket.connect(new InetSocketAddress("127.0.0.1", socksPort),
					30_000);
			socket.setSoTimeout(30_000);
			TorSocks.connect(socket, HOST, PORT, "zw-" + isolationTag,
					isolationTag);

			OutputStream out = socket.getOutputStream();
			String request = "GET /api/v1/prices HTTP/1.0\r\n"
					+ "Host: " + HOST + "\r\n"
					+ "Accept: application/json\r\n"
					+ "Connection: close\r\n\r\n";
			out.write(request.getBytes(StandardCharsets.US_ASCII));
			out.flush();

			ByteArrayOutputStream buf = new ByteArrayOutputStream();
			InputStream in = socket.getInputStream();
			byte[] tmp = new byte[4096];
			int r;
			while ((r = in.read(tmp)) >= 0) {
				buf.write(tmp, 0, r);
				if (buf.size() > 65536) {
					break;
				}
			}

			String resp = new String(buf.toByteArray(),
					StandardCharsets.UTF_8);
			int firstLineEnd = resp.indexOf("\r\n");
			String statusLine = firstLineEnd >= 0
					? resp.substring(0, firstLineEnd) : resp;
			if (!statusLine.contains(" 200")) {
				throw new IOException("price http non-200");
			}
			int sep = resp.indexOf("\r\n\r\n");
			String body = sep >= 0 ? resp.substring(sep + 4) : resp;
			java.util.Map<String, Double> map = new java.util.HashMap<>();
			for (String c : CURRENCIES) {
				double v = parseValue(body, c);
				if (v > 0) {
					map.put(c, v);
				}
			}
			return new Rates(map);
		} finally {
			try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
	}

	private static double parseValue(String body, String key) {
		int i = body.indexOf("\"" + key + "\"");
		if (i < 0) {
			return 0;
		}
		int colon = body.indexOf(':', i);
		if (colon < 0) {
			return 0;
		}
		int j = colon + 1;
		while (j < body.length() && body.charAt(j) == ' ') {
			j++;
		}
		int k = j;
		while (k < body.length() && (Character.isDigit(body.charAt(k))
				|| body.charAt(k) == '.')) {
			k++;
		}
		try {
			double v = Double.parseDouble(body.substring(j, k));
			if (!Double.isFinite(v) || v < 100 || v > 1e12) {
				return 0;
			}
			return v;
		} catch (Exception e) {
			return 0;
		}
	}
}
