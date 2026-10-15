package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public final class SilentPaymentCoins {

	private final Object lock = new Object();
	@GuardedBy("lock")
	private final List<SilentPaymentScanner.Found> coins = new ArrayList<>();
	@GuardedBy("lock")
	@Nullable
	private String walletId;

	public void clear() {
		synchronized (lock) {
			coins.clear();
			walletId = null;
		}
	}

	public void load(String walletId, @Nullable JSONArray stored)
			throws JSONException {
		synchronized (lock) {
			coins.clear();
			this.walletId = walletId;
			if (stored == null) return;
			for (int i = 0; i < stored.length(); i++) {
				JSONObject u = stored.getJSONObject(i);
				coins.add(new SilentPaymentScanner.Found(u.getString("t"),
						u.getInt("v"), u.getLong("val"), hex(u.getString("x")),
						hex(u.getString("w"))));
			}
		}
	}

	@Nullable
	public JSONArray addFound(String walletId,
			List<SilentPaymentScanner.Found> found) throws JSONException {
		synchronized (lock) {
			if (this.walletId != null && !this.walletId.equals(walletId)) {
				return null;
			}
			this.walletId = walletId;
			for (SilentPaymentScanner.Found f : found) {
				boolean dup = false;
				for (SilentPaymentScanner.Found e : coins) {
					if (e.txid.equals(f.txid) && e.vout == f.vout) {
						dup = true;
						break;
					}
				}
				if (!dup) {
					coins.add(f);
				}
			}
			return toJson();
		}
	}

	public List<SilentPaymentScanner.Found> snapshot(String walletId) {
		synchronized (lock) {
			return walletId.equals(this.walletId) ? new ArrayList<>(coins)
					: new ArrayList<>();
		}
	}

	public void spent() {
		synchronized (lock) {
			coins.clear();
		}
	}

	public long[] totals() {
		synchronized (lock) {
			long bal = 0;
			for (SilentPaymentScanner.Found f : coins) {
				bal += f.valueSat;
			}
			return new long[] {bal, coins.size()};
		}
	}

	@GuardedBy("lock")
	private JSONArray toJson() throws JSONException {
		JSONArray arr = new JSONArray();
		for (SilentPaymentScanner.Found f : coins) {
			JSONObject u = new JSONObject();
			u.put("t", f.txid);
			u.put("v", f.vout);
			u.put("val", f.valueSat);
			u.put("x", hexString(f.xonly));
			u.put("w", hexString(f.tweak));
			arr.put(u);
		}
		return arr;
	}

	private static byte[] hex(String h) {
		byte[] out = new byte[h.length() / 2];
		for (int i = 0; i < out.length; i++) {
			out[i] = (byte) Integer.parseInt(h.substring(i * 2, i * 2 + 2), 16);
		}
		return out;
	}

	private static String hexString(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) {
			sb.append(Character.forDigit((x >> 4) & 0xF, 16));
			sb.append(Character.forDigit(x & 0xF, 16));
		}
		return sb.toString();
	}
}
