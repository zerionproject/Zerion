package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Locale;

import javax.annotation.Nullable;

/**
 * The address and optional amount of a scanned payment request. A plain
 * address is returned as is. A BIP21 URI yields its address and amount; a
 * URI carrying a parameter marked as required with the {@code req-} prefix
 * that this wallet does not understand is refused, as BIP21 demands, rather
 * than paid as if the parameter were absent.
 */
@NotNullByDefault
public final class Bip21Request {

	public final String address;
	@Nullable
	public final String amount;

	private Bip21Request(String address, @Nullable String amount) {
		this.address = address;
		this.amount = amount;
	}

	/** Null when the request carries a required parameter we do not know. */
	@Nullable
	public static Bip21Request parse(String raw) {
		String addr = raw.trim();
		String amt = null;
		if (!addr.toLowerCase(Locale.US).startsWith("bitcoin:")) {
			return new Bip21Request(addr, null);
		}
		addr = addr.substring("bitcoin:".length());
		int q = addr.indexOf('?');
		String query = "";
		if (q >= 0) {
			query = addr.substring(q + 1);
			addr = addr.substring(0, q);
		}
		if (query.isEmpty()) {
			return new Bip21Request(addr, null);
		}
		for (String param : query.split("&")) {
			int eq = param.indexOf('=');
			String key = eq < 0 ? param : param.substring(0, eq);
			if (key.regionMatches(true, 0, "req-", 0, 4)) {
				return null;
			}
			if (eq > 0 && key.equalsIgnoreCase("amount")) {
				amt = decode(param.substring(eq + 1));
			}
		}
		return new Bip21Request(addr, amt);
	}

	private static String decode(String s) {
		try {
			return URLDecoder.decode(s, "UTF-8");
		} catch (UnsupportedEncodingException | IllegalArgumentException e) {
			return s;
		}
	}
}
