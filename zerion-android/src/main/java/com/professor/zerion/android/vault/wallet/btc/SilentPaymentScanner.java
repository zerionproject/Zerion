package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

@NotNullByDefault
public final class SilentPaymentScanner {

	private static final Pattern TWEAK = Pattern.compile("[0-9a-fA-F]{66}");
	private static final Pattern TXID = Pattern.compile("[0-9a-fA-F]{64}");
	private static final Pattern HEX = Pattern.compile("(?:[0-9a-fA-F]{2})*");
	private static final Pattern P2TR = Pattern.compile("5120[0-9a-fA-F]{64}");
	static final int MAX_HEIGHT = 100_000_000;

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
		try {
			return (int) StrictJson.integer(StrictJson.object(
					StrictJson.parse(r)).get("block_height"), 0, MAX_HEIGHT);
		} catch (IOException malformed) {
			return null;
		}
	}

	private static final class Candidate {
		final String txid;
		final int vout;
		final long value;
		final byte[] xonly;

		Candidate(String txid, int vout, long value, byte[] xonly) {
			this.txid = txid;
			this.vout = vout;
			this.value = value;
			this.xonly = xonly;
		}
	}

	private static IOException failed(int height) {
		return new IOException("silent payment source failed at block "
				+ height);
	}

	private static List<Object> document(@Nullable String response, int height)
			throws IOException {
		if (response == null) {
			throw failed(height);
		}
		try {
			return StrictJson.array(StrictJson.parse(response));
		} catch (IOException malformed) {
			throw failed(height);
		}
	}

	private static List<String> tweaks(List<Object> items, int height)
			throws IOException {
		List<String> tweaks = new ArrayList<>();
		try {
			for (Object item : items) {
				String tweak = StrictJson.string(item);
				if (!TWEAK.matcher(tweak).matches()) {
					throw failed(height);
				}
				tweaks.add(tweak);
			}
		} catch (IOException malformed) {
			throw failed(height);
		}
		return tweaks;
	}

	private static List<Candidate> candidates(List<Object> items, int height)
			throws IOException {
		List<Candidate> out = new ArrayList<>();
		try {
			for (Object item : items) {
				Map<String, Object> o = StrictJson.object(item);
				String spk = StrictJson.string(o.get("scriptpubkey"));
				if (!HEX.matcher(spk).matches()) {
					throw failed(height);
				}
				if (!P2TR.matcher(spk).matches()) {
					continue;
				}
				String txid = StrictJson.string(o.get("txid"));
				if (!TXID.matcher(txid).matches()) {
					throw failed(height);
				}
				int vout = (int) StrictJson.integer(o.get("vout"), 0,
						Integer.MAX_VALUE);
				long value = StrictJson.integer(o.get("value"), 0,
						BtcAmounts.MAX_MONEY_SAT);
				out.add(new Candidate(txid.toLowerCase(Locale.ROOT), vout,
						value, hexToBytes(spk.substring(4))));
			}
		} catch (IOException malformed) {
			throw failed(height);
		}
		return out;
	}

	public static List<Found> scanBlock(String base, int height, byte[] scanPriv,
			byte[] spendPub, String isolationTag, Fetcher fetcher)
			throws IOException {
		List<Found> found = new ArrayList<>();
		List<String> tweaks = tweaks(document(fetcher.get(
				url(base, "tweaks/" + height), isolationTag), height), height);
		if (tweaks.isEmpty()) {
			return found;
		}
		List<Candidate> candidates = candidates(document(fetcher.get(
				url(base, "utxos/" + height), isolationTag), height), height);
		List<byte[]> outXonlys = new ArrayList<>();
		for (Candidate c : candidates) {
			outXonlys.add(c.xonly);
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
						Candidate m = candidates.get(i);
						boolean dup = false;
						for (Found f : found) {
							if (f.txid.equals(m.txid) && f.vout == m.vout) {
								dup = true;
								break;
							}
						}
						if (!dup) {
							found.add(new Found(m.txid, m.vout, m.value,
									m.xonly, d.tweak));
						}
						break;
					}
				}
			}
		}
		return found;
	}

	private static String url(String base, String path) {
		String b = base.trim();
		while (b.endsWith("/")) {
			b = b.substring(0, b.length() - 1);
		}
		if (b.regionMatches(true, 0, "http://", 0, 7)) {
			b = b.substring(7);
		}
		String full = b.regionMatches(true, 0, "https://", 0, 8)
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
}
