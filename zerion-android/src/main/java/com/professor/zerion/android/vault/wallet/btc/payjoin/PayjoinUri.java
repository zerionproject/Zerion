package com.professor.zerion.android.vault.wallet.btc.payjoin;

import org.briarproject.nullsafety.NotNullByDefault;

import java.math.BigDecimal;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

/**
 * Detects Payjoin information in a BIP21 payment request. A request without a
 * usable Payjoin endpoint is a normal payment and continues on the existing
 * send path unchanged. A request whose Payjoin data is present but malformed is
 * reported as malformed so the caller can reject the Payjoin attempt; nothing
 * here trusts the endpoint. The endpoint is validated again by the native
 * Payjoin implementation before it is used.
 */
@NotNullByDefault
public final class PayjoinUri {

	public enum Kind {
		NORMAL,
		PAYJOIN,
		MALFORMED
	}

	public final Kind kind;
	public final String address;
	public final long amountSat;
	@Nullable
	public final String pjEndpoint;
	public final boolean outputSubstitution;

	private PayjoinUri(Kind kind, String address, long amountSat,
			@Nullable String pjEndpoint, boolean outputSubstitution) {
		this.kind = kind;
		this.address = address;
		this.amountSat = amountSat;
		this.pjEndpoint = pjEndpoint;
		this.outputSubstitution = outputSubstitution;
	}

	public static PayjoinUri detect(String uri) {
		String trimmed = uri.trim();
		int colon = trimmed.indexOf(':');
		if (colon < 0 || !trimmed.substring(0, colon)
				.equalsIgnoreCase("bitcoin")) {
			return new PayjoinUri(Kind.NORMAL, "", 0, null, false);
		}
		String rest = trimmed.substring(colon + 1);
		int q = rest.indexOf('?');
		String address = q < 0 ? rest : rest.substring(0, q);
		String query = q < 0 ? "" : rest.substring(q + 1);

		long amountSat = 0;
		String pj = null;
		boolean pjos = false;
		if (!query.isEmpty()) {
			for (String pair : query.split("&")) {
				int eq = pair.indexOf('=');
				String key = eq < 0 ? pair : pair.substring(0, eq);
				String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
				if (key.equalsIgnoreCase("amount")) {
					amountSat = parseAmount(value);
				} else if (key.equalsIgnoreCase("pj")) {
					pj = value;
				} else if (key.equalsIgnoreCase("pjos")) {
					pjos = value.equals("1");
				}
			}
		}

		if (pj == null) {
			return new PayjoinUri(Kind.NORMAL, address, amountSat, null, false);
		}
		if (!isValidEndpoint(pj) || address.isEmpty()) {
			return new PayjoinUri(Kind.MALFORMED, address, amountSat, null,
					false);
		}
		return new PayjoinUri(Kind.PAYJOIN, address, amountSat, pj, pjos);
	}

	public boolean isPayjoin() {
		return kind == Kind.PAYJOIN;
	}

	private static boolean isValidEndpoint(String pj) {
		String lower = pj.toLowerCase(java.util.Locale.ROOT);
		if (!lower.startsWith("https://") && !lower.startsWith("http://")) {
			return false;
		}
		int schemeEnd = pj.indexOf("://") + 3;
		int hostEnd = pj.length();
		for (int i = schemeEnd; i < pj.length(); i++) {
			char c = pj.charAt(i);
			if (c == '/' || c == '?' || c == '#') {
				hostEnd = i;
				break;
			}
		}
		String host = pj.substring(schemeEnd, hostEnd);
		return !host.isEmpty() && !host.contains(" ");
	}

	private static long parseAmount(String value) {
		try {
			BigDecimal btc = new BigDecimal(value);
			return btc.movePointRight(8).longValueExact();
		} catch (Exception e) {
			return 0;
		}
	}

	private static String decode(String value) {
		try {
			return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
		} catch (Exception e) {
			return value;
		}
	}
}
