package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;

@NotNullByDefault
public final class MoneroUri {

	private static final String SCHEME = "monero:";
	private static final long ATOMIC_PER_XMR = 1_000_000_000_000L;
	private static final int XMR_DECIMALS = 12;

	private final String address;
	private final long amountAtomic;

	private MoneroUri(String address, long amountAtomic) {
		this.address = address;
		this.amountAtomic = amountAtomic;
	}

	public String address() {
		return address;
	}

	public long amountAtomic() {
		return amountAtomic;
	}

	public boolean hasAmount() {
		return amountAtomic >= 0;
	}

	@Nullable
	public static MoneroUri parse(String input) throws XmrError.XmrException {
		String s = input.trim();
		if (s.isEmpty()) return null;
		if (!s.startsWith(SCHEME)) {
			return new MoneroUri(s, -1);
		}
		String body = s.substring(SCHEME.length());
		int q = body.indexOf('?');
		String address = q < 0 ? body : body.substring(0, q);
		address = decode(address);
		if (address.isEmpty()) return null;

		long amount = -1;
		if (q >= 0) {
			String query = body.substring(q + 1);
			for (String pair : query.split("&")) {
				int eq = pair.indexOf('=');
				if (eq < 0) continue;
				String key = pair.substring(0, eq);
				String value = decode(pair.substring(eq + 1));
				if ("tx_amount".equals(key)) {
					amount = parseXmrToAtomic(value);
				}
			}
		}
		return new MoneroUri(address, amount);
	}

	public static long parseXmrToAtomic(String xmr) throws XmrError.XmrException {
		if (xmr.isEmpty()) throw invalidAmount();
		int dot = xmr.indexOf('.');
		String whole = dot < 0 ? xmr : xmr.substring(0, dot);
		String frac = dot < 0 ? "" : xmr.substring(dot + 1);
		if (whole.isEmpty() && frac.isEmpty()) throw invalidAmount();
		if (frac.length() > XMR_DECIMALS) throw invalidAmount();
		if (!isDigits(whole) || !isDigits(frac)) throw invalidAmount();

		StringBuilder padded = new StringBuilder(frac);
		while (padded.length() < XMR_DECIMALS) padded.append('0');

		try {
			long wholePart = whole.isEmpty() ? 0 : Long.parseLong(whole);
			long fracPart = padded.length() == 0 ? 0
					: Long.parseLong(padded.toString());
			long atomic = Math.addExact(
					Math.multiplyExact(wholePart, ATOMIC_PER_XMR), fracPart);
			if (atomic < 0) throw invalidAmount();
			return atomic;
		} catch (NumberFormatException | ArithmeticException e) {
			throw invalidAmount();
		}
	}

	private static boolean isDigits(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < '0' || c > '9') return false;
		}
		return true;
	}

	private static String decode(String s) {
		try {
			return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException | IllegalArgumentException e) {
			return s;
		}
	}

	private static XmrError.XmrException invalidAmount() {
		return new XmrError.XmrException(XmrError.SEND_SNAPSHOT_INVALID);
	}
}
