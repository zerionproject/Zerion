package com.professor.zerion.android.vault.wallet.xmr;

import com.professor.zerion.android.vault.wallet.btc.StrictJson;
import com.professor.zerion.android.vault.wallet.btc.TorHttp;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

@NotNullByDefault
public final class XmrPrice {

	private static final String USD_URL =
			"https://api.kraken.com/0/public/Ticker?pair=XMRUSD";
	private static final String EUR_URL =
			"https://api.kraken.com/0/public/Ticker?pair=XMREUR";
	private static final Pattern DECIMAL =
			Pattern.compile("[0-9]{1,15}(?:\\.[0-9]{1,12})?");
	static final double MIN_PRICE = 1;
	static final double MAX_PRICE = 1_000_000;

	public static final class Rates {
		public final double usd;
		public final double eur;

		public Rates(double usd, double eur) {
			this.usd = plausible(usd) ? usd : 0;
			this.eur = plausible(eur) ? eur : 0;
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

	static boolean plausible(double price) {
		return Double.isFinite(price) && price >= MIN_PRICE
				&& price <= MAX_PRICE;
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
		try {
			Map<String, Object> root =
					StrictJson.object(StrictJson.parse(body));
			if (!StrictJson.array(root.get("error")).isEmpty()) {
				return 0;
			}
			Map<String, Object> result = StrictJson.object(root.get("result"));
			if (result.size() != 1) {
				return 0;
			}
			List<Object> last = StrictJson.array(StrictJson.object(
					result.values().iterator().next()).get("c"));
			if (last.isEmpty()) {
				return 0;
			}
			String price = StrictJson.string(last.get(0));
			if (!DECIMAL.matcher(price).matches()) {
				return 0;
			}
			double v = Double.parseDouble(price);
			return plausible(v) ? v : 0;
		} catch (IOException malformed) {
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
