package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@NotNullByDefault
public final class BtcPrice {

	private static final String HOST =
			"mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion";
	private static final int PORT = 80;
	static final int MAX_BODY = 64 * 1024;
	static final int READ_TIMEOUT_MS = 30_000;
	static final int RESPONSE_DEADLINE_MS = 60_000;

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
			try {
				return parse(json);
			} catch (IOException malformed) {
				return new Rates(new java.util.HashMap<>());
			}
		}

		static Rates parse(String json) throws IOException {
			Map<String, Object> o = StrictJson.object(StrictJson.parse(json));
			java.util.Map<String, Double> m = new java.util.HashMap<>();
			for (String c : CURRENCIES) {
				Object v = o.get(c);
				if (v instanceof BigDecimal) {
					double d = ((BigDecimal) v).doubleValue();
					if (Double.isFinite(d) && d >= 100 && d <= 1e12) {
						m.put(c, d);
					}
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
		Socket socket = com.professor.zerion.android.vault.net.TorSockets
				.open(socksPort, READ_TIMEOUT_MS);
		try {
			TorSocks.connect(socket, HOST, PORT, "zw-" + isolationTag,
					isolationTag);

			OutputStream out = socket.getOutputStream();
			String request = "GET /api/v1/prices HTTP/1.0\r\n"
					+ "Host: " + HOST + "\r\n"
					+ "Accept: application/json\r\n"
					+ "Connection: close\r\n\r\n";
			out.write(request.getBytes(StandardCharsets.US_ASCII));
			out.flush();

			return Rates.parse(TorHttp.readResponse(socket, MAX_BODY,
					READ_TIMEOUT_MS, RESPONSE_DEADLINE_MS));
		} finally {
			try {
				socket.close();
			} catch (IOException ignored) {
			}
		}
	}
}
