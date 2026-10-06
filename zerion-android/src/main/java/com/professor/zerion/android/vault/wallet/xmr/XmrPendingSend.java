package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@NotNullByDefault
public final class XmrPendingSend {

	public final String walletId;
	public final String[] txids;
	public final long amountAtomic;
	public final long feeAtomic;
	public final long totalDebitAtomic;
	public final long reservedInputAtomic;
	public final long createdAtMs;
	public final boolean uncertain;
	public final boolean converged;

	public XmrPendingSend(String walletId, String[] txids, long amountAtomic,
			long feeAtomic, long totalDebitAtomic, long reservedInputAtomic,
			long createdAtMs, boolean uncertain, boolean converged) {
		this.walletId = walletId;
		this.txids = txids;
		this.amountAtomic = amountAtomic;
		this.feeAtomic = feeAtomic;
		this.totalDebitAtomic = totalDebitAtomic;
		this.reservedInputAtomic = Math.max(reservedInputAtomic, totalDebitAtomic);
		this.createdAtMs = createdAtMs;
		this.uncertain = uncertain;
		this.converged = converged;
	}

	public XmrPendingSend(String walletId, String[] txids, long amountAtomic,
			long feeAtomic, long totalDebitAtomic, long createdAtMs,
			boolean uncertain, boolean converged) {
		this(walletId, txids, amountAtomic, feeAtomic, totalDebitAtomic,
				totalDebitAtomic, createdAtMs, uncertain, converged);
	}

	public long reservationDebit() {
		return converged ? 0 : reservedInputAtomic;
	}

	public enum ReservationState { RESERVED, RELAY_UNCERTAIN, CONVERGED }

	public ReservationState reservationState() {
		if (converged) return ReservationState.CONVERGED;
		return uncertain ? ReservationState.RELAY_UNCERTAIN
				: ReservationState.RESERVED;
	}

	public XmrPendingSend rebind(String newWalletId) {
		return new XmrPendingSend(newWalletId, txids, amountAtomic, feeAtomic,
				totalDebitAtomic, reservedInputAtomic, createdAtMs, uncertain,
				converged);
	}

	public XmrPendingSend asConverged() {
		return new XmrPendingSend(walletId, txids, amountAtomic, feeAtomic,
				totalDebitAtomic, reservedInputAtomic, createdAtMs, uncertain,
				true);
	}

	private JSONObject toJsonObject() throws Exception {
		JSONObject o = new JSONObject();
		o.put("w", walletId);
		JSONArray a = new JSONArray();
		for (String id : txids) a.put(id);
		o.put("t", a);
		o.put("amt", amountAtomic);
		o.put("fee", feeAtomic);
		o.put("deb", totalDebitAtomic);
		o.put("ri", reservedInputAtomic);
		o.put("at", createdAtMs);
		o.put("unc", uncertain);
		o.put("cv", converged);
		return o;
	}

	public String toJson() {
		try {
			return toJsonObject().toString();
		} catch (Throwable e) {
			return "";
		}
	}

	@Nullable
	private static XmrPendingSend fromJsonObject(JSONObject o) {
		String walletId = o.optString("w", "");
		if (walletId.isEmpty()) return null;
		JSONArray a = o.optJSONArray("t");
		if (a == null || a.length() < 1) return null;
		String[] txids = new String[a.length()];
		for (int i = 0; i < a.length(); i++) {
			String id = a.optString(i, "");
			if (!XmrTxLookup.isTxidHex(id)) return null;
			txids[i] = id;
		}
		long amt = o.optLong("amt", -1);
		long fee = o.optLong("fee", -1);
		long deb = o.optLong("deb", -1);
		long at = o.optLong("at", 0);
		if (amt < 0 || fee < 0 || deb < 0 || at <= 0) return null;
		if (deb < amt + fee) return null;
		long ri = o.optLong("ri", deb);
		if (ri < deb) ri = deb;
		return new XmrPendingSend(walletId, txids, amt, fee, deb, ri, at,
				o.optBoolean("unc", false), o.optBoolean("cv", false));
	}

	@Nullable
	public static XmrPendingSend fromJson(@Nullable String json) {
		if (json == null || json.isEmpty()) return null;
		try {
			return fromJsonObject(new JSONObject(json));
		} catch (Throwable e) {
			return null;
		}
	}

	public static String listToJson(List<XmrPendingSend> list) {
		try {
			JSONArray a = new JSONArray();
			for (XmrPendingSend p : list) a.put(p.toJsonObject());
			return a.toString();
		} catch (Throwable e) {
			return "";
		}
	}

	public static List<XmrPendingSend> listFromJson(@Nullable String json) {
		List<XmrPendingSend> out = new ArrayList<>();
		if (json == null || json.isEmpty()) return out;
		try {
			String s = json.trim();
			if (s.startsWith("[")) {
				JSONArray a = new JSONArray(s);
				for (int i = 0; i < a.length(); i++) {
					JSONObject o = a.optJSONObject(i);
					if (o != null) {
						XmrPendingSend p = fromJsonObject(o);
						if (p != null) out.add(p);
					}
				}
			} else {
				XmrPendingSend p = fromJsonObject(new JSONObject(s));
				if (p != null) out.add(p);
			}
		} catch (Throwable ignored) {
		}
		return out;
	}

	public XmrTxInfo pendingRow(String txid) {
		return XmrTxInfo.pendingOutgoing(txid, amountAtomic, feeAtomic,
				createdAtMs / 1000L, uncertain);
	}

	public XmrTxInfo historyRow(String txid, @Nullable XmrTxInfo canonical) {
		if (canonical == null) return pendingRow(txid);
		long ts = canonical.timestamp > 0 ? canonical.timestamp : createdAtMs / 1000L;
		return XmrTxInfo.outgoing(txid, amountAtomic, feeAtomic, ts,
				canonical.height, canonical.confirmations, canonical.pending,
				canonical.failed);
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (!(o instanceof XmrPendingSend)) return false;
		XmrPendingSend p = (XmrPendingSend) o;
		return amountAtomic == p.amountAtomic && feeAtomic == p.feeAtomic
				&& totalDebitAtomic == p.totalDebitAtomic
				&& reservedInputAtomic == p.reservedInputAtomic
				&& createdAtMs == p.createdAtMs && uncertain == p.uncertain
				&& converged == p.converged
				&& walletId.equals(p.walletId)
				&& Arrays.equals(txids, p.txids);
	}

	@Override
	public int hashCode() {
		return walletId.hashCode() * 31 + Arrays.hashCode(txids);
	}
}
