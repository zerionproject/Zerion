package com.professor.zerion.android.vault.wallet.xmr;

import com.professor.zerion.android.vault.wallet.btc.TorHttp;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class XmrPrice {

	private static final String USD_URL =
			"https://api.kraken.com/0/public/Ticker?pair=XMRUSD";
	private static final String EUR_URL =
			"https://api.kraken.com/0/public/Ticker?pair=XMREUR";

	public static final class Rates {
		public final double usd;
		public final double eur;

		public Rates(double usd, double eur) {
			this.usd = usd > 0 ? usd : 0;
			this.eur = eur > 0 ? eur : 0;
		}

		public double get(String currency) {
			return "EUR".equals(currency) ? eur : usd;
		}

		public boolean has(String currency) {
			return get(currency) > 0;
		}

		public boolean isEmpty() {
			return usd <= 0 && eur <= 0;
		}

		public String toJson() {
			return "{\"usd\":" + usd + ",\"eur\":" + eur + "}";
		}

		public static Rates fromJson(String json) {
			return new Rates(parseNumber(json, "usd"),
					parseNumber(json, "eur"));
		}
	}

	private XmrPrice() {
	}

	@Nullable
	public static Rates fetch(int socksPort, String isolationTag) {
		double usd = fetchLast(USD_URL, socksPort, isolationTag);
		double eur = fetchLast(EUR_URL, socksPort, isolationTag);
		if (usd <= 0 && eur <= 0) {
			return null;
		}
		return new Rates(usd, eur);
	}

	private static double fetchLast(String url, int socksPort,
			String isolationTag) {
		String body = TorHttp.get(url, socksPort, isolationTag);
		return body == null ? 0 : parseKrakenLast(body);
	}

	static double parseKrakenLast(String body) {
		int c = body.indexOf("\"c\"");
		if (c < 0) {
			return 0;
		}
		int open = body.indexOf('[', c);
		if (open < 0) {
			return 0;
		}
		int q1 = body.indexOf('"', open);
		if (q1 < 0) {
			return 0;
		}
		int q2 = body.indexOf('"', q1 + 1);
		if (q2 < 0) {
			return 0;
		}
		try {
			double v = Double.parseDouble(body.substring(q1 + 1, q2));
			return v > 0 ? v : 0;
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	static double parseNumber(String json, String key) {
		int i = json.indexOf("\"" + key + "\":");
		if (i < 0) {
			return 0;
		}
		int s = i + key.length() + 3;
		int e = s;
		while (e < json.length()
				&& "0123456789.eE+-".indexOf(json.charAt(e)) >= 0) {
			e++;
		}
		if (e <= s) {
			return 0;
		}
		try {
			double v = Double.parseDouble(json.substring(s, e));
			return v > 0 ? v : 0;
		} catch (NumberFormatException ex) {
			return 0;
		}
	}
}
