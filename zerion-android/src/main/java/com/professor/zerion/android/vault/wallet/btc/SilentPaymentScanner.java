package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

@NotNullByDefault
public final class SilentPaymentScanner {

	private static final Pattern TWEAK = Pattern.compile("[0-9a-fA-F]{66}");
	private static final Pattern OBJECT = Pattern.compile("\\{[^{}]*\\}");

	private SilentPaymentScanner() {
	}

	@NotNullByDefault
	public interface Fetcher {
		@Nullable
		String get(String url, String isolationTag);
	}

	public static final class Found {
		public final String txid;
		public final int vout;
		public final long valueSat;
		public final byte[] xonly;
		public final byte[] tweak;

		public Found(String txid, int vout, long valueSat, byte[] xonly,
				byte[] tweak) {
			this.txid = txid;
			this.vout = vout;
			this.valueSat = valueSat;
			this.xonly = xonly;
			this.tweak = tweak;
		}
	}

	@Nullable
	public static Integer tipHeight(String base, String isolationTag,
			Fetcher fetcher) {
		String r = fetcher.get(url(base, "block-height"), isolationTag);
		if (r == null) {
			return null;
		}
		Long h = numField(r, "block_height");
		return h == null ? null : h.intValue();
	}

	public static List<Found> scanBlock(String base, int height, byte[] scanPriv,
			byte[] spendPub, String isolationTag, Fetcher fetcher)
			throws IOException {
		List<Found> found = new ArrayList<>();
		String tweaksResp = fetcher.get(url(base, "tweaks/" + height),
				isolationTag);
		if (tweaksResp == null || !jsonLike(tweaksResp)) {
			throw new IOException("silent payment source failed at block "
					+ height);
		}
		List<String> tweaks = new ArrayList<>();
		Matcher tm = TWEAK.matcher(tweaksResp);
		while (tm.find()) {
			tweaks.add(tm.group());
		}
		if (tweaks.isEmpty()) {
			return found;
		}
		String utxosResp = fetcher.get(url(base, "utxos/" + height),
				isolationTag);
		if (utxosResp == null || !jsonLike(utxosResp)) {
			throw new IOException("silent payment source failed at block "
					+ height);
		}

		List<String[]> utxoMeta = new ArrayList<>();
		List<byte[]> outXonlys = new ArrayList<>();
		Matcher om = OBJECT.matcher(utxosResp);
		while (om.find()) {
			String o = om.group();
			String spk = strField(o, "scriptpubkey");
			if (spk == null || !spk.startsWith("5120") || spk.length() < 68) {
				continue;
			}
			String txid = strField(o, "txid");
			Long vout = numField(o, "vout");
			if (txid == null || vout == null) {
				continue;
			}
			Long value = numField(o, "value");
			byte[] xonly = hexToBytes(spk.substring(4, 68));
			utxoMeta.add(new String[]{txid, String.valueOf(vout.intValue()),
					String.valueOf(value == null ? 0L : value)});
			outXonlys.add(xonly);
		}
		if (outXonlys.isEmpty()) {
			return found;
		}

		for (String twHex : tweaks) {
			List<SilentPayment.Detected> detected;
			try {
				detected = SilentPayment.scanWithTweak(hexToBytes(twHex),
						scanPriv, spendPub, outXonlys);
			} catch (Throwable e) {
				continue;
			}
			for (SilentPayment.Detected d : detected) {
				for (int i = 0; i < outXonlys.size(); i++) {
					if (java.util.Arrays.equals(outXonlys.get(i),
							d.outputXOnly)) {
						String[] m = utxoMeta.get(i);
						boolean dup = false;
						for (Found f : found) {
							if (f.txid.equals(m[0])
									&& f.vout == Integer.parseInt(m[1])) {
								dup = true;
								break;
							}
						}
						if (!dup) {
							found.add(new Found(m[0], Integer.parseInt(m[1]),
									Long.parseLong(m[2]), outXonlys.get(i),
									d.tweak));
						}
						break;
					}
				}
			}
		}
		return found;
	}

	private static boolean jsonLike(String s) {
		return s.indexOf('{') >= 0 || s.indexOf('[') >= 0;
	}

	private static String url(String base, String path) {
		String b = base.trim();
		while (b.endsWith("/")) {
			b = b.substring(0, b.length() - 1);
		}
		String full = (b.startsWith("http://") || b.startsWith("https://"))
				? b : "https://" + b;
		return full + "/" + path;
	}

	private static byte[] hexToBytes(String h) {
		byte[] out = new byte[h.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	@Nullable
	private static String strField(String json, String key) {
		String m = "\"" + key + "\":\"";
		int i = json.indexOf(m);
		if (i < 0) {
			return null;
		}
		int start = i + m.length();
		int end = json.indexOf('"', start);
		return end < 0 ? null : json.substring(start, end);
	}

	@Nullable
	private static Long numField(String json, String key) {
		String m = "\"" + key + "\":";
		int i = json.indexOf(m);
		if (i < 0) {
			return null;
		}
		int s = i + m.length();
		if (s < json.length() && json.charAt(s) == '"') {
			s++;
		}
		int e = s;
		while (e < json.length()
				&& (Character.isDigit(json.charAt(e)) || json.charAt(e) == '-')) {
			e++;
		}
		try {
			return Long.parseLong(json.substring(s, e));
		} catch (Exception ex) {
			return null;
		}
	}
}
