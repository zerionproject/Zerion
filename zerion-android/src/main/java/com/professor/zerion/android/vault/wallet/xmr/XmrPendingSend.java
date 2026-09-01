package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Durable record of a relayed-but-not-yet-canonical outgoing transaction set,
 * built from the exact signed transaction (never guessed). It carries only what
 * the UI overlay needs to show the outgoing row and to reserve the spent funds
 * until wallet2's own history observes the same txid(s): the txids, the exact
 * amount, the exact final fee, the total debit, when it was accepted, and
 * whether the relay outcome was uncertain. All fields are public chain data (no
 * key material, destination, credential or snapshot), so the record is stored
 * alongside the wallet's other settings.
 *
 * <p>A Monero background/view-only wallet cannot compute key images and so
 * cannot itself recognise its outgoing spend, which would otherwise leave the
 * just-spent funds displayed as still available. The record therefore drives two
 * INDEPENDENT lifetimes: the synthetic outgoing history row is shown until
 * canonical history observes the txid, while the balance reservation is held
 * until the spend wallet's post-relay state has been written back into the
 * background cache ({@link #converged}) so the background wallet's own balance
 * canonically excludes the spent outputs. Observing the txid in history never,
 * by itself, releases the reservation.
 */
@NotNullByDefault
public final class XmrPendingSend {

	public final String walletId;
	public final String[] txids;
	public final long amountAtomic;
	public final long feeAtomic;
	public final long totalDebitAtomic;
	/**
	 * The total value of the inputs the transaction consumes: amount + fee +
	 * change. This, not the net debit, is what must be reserved from the
	 * displayed balance of a view-only background wallet. That wallet cannot
	 * compute key images, so until convergence it still counts every consumed
	 * input as unspent AND additionally scans the change output back in; reserving
	 * only the net debit (amount + fee) would leave the change value displayed as
	 * spendable. Never smaller than {@link #totalDebitAtomic}.
	 */
	public final long reservedInputAtomic;
	public final long createdAtMs;
	public final boolean uncertain;
	/**
	 * True once the spend wallet's post-relay state has been written back into
	 * the view-only background cache, so the background wallet's own balance now
	 * excludes the spent outputs. Only then is the balance reservation no longer
	 * required, and dropping it cannot make the spent funds reappear as
	 * spendable. This is durable, independent of history observation.
	 */
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

	/** Back-compat constructor for callers without the exact input sum: reserves
	 *  the net debit (the pre-change-reservation behavior). */
	public XmrPendingSend(String walletId, String[] txids, long amountAtomic,
			long feeAtomic, long totalDebitAtomic, long createdAtMs,
			boolean uncertain, boolean converged) {
		this(walletId, txids, amountAtomic, feeAtomic, totalDebitAtomic,
				totalDebitAtomic, createdAtMs, uncertain, converged);
	}

	/**
	 * The debit to reserve from the displayed balance: the exact consumed-input
	 * total until the background wallet's own balance has canonically incorporated
	 * the spend ({@link #converged}); zero afterward, so a converged send is never
	 * double-subtracted. History observation alone never releases it.
	 */
	public long reservationDebit() {
		return converged ? 0 : reservedInputAtomic;
	}

	/** A copy bound to a new wallet id, used when a rename re-seals the wallet
	 *  under a new id so its outgoing history is carried over, not orphaned. */
	public XmrPendingSend rebind(String newWalletId) {
		return new XmrPendingSend(newWalletId, txids, amountAtomic, feeAtomic,
				totalDebitAtomic, reservedInputAtomic, createdAtMs, uncertain,
				converged);
	}

	/** A copy marked converged (spend state written into the background cache). */
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

	/**
	 * Parse a persisted record, returning null (treated as "no overlay") only on
	 * a structurally invalid record. A malformed overlay must never fabricate a
	 * balance reservation, so every field is validated: txids are 64-hex, amounts
	 * are non-negative and consistent (total debit at least amount + fee).
	 */
	@Nullable
	public static XmrPendingSend fromJson(@Nullable String json) {
		if (json == null || json.isEmpty()) return null;
		try {
			return fromJsonObject(new JSONObject(json));
		} catch (Throwable e) {
			return null;
		}
	}

	/** Encode a set of outstanding sends. Every send in flight is retained so a
	 *  later send never discards an earlier, still-unresolved reservation. */
	public static String listToJson(List<XmrPendingSend> list) {
		try {
			JSONArray a = new JSONArray();
			for (XmrPendingSend p : list) a.put(p.toJsonObject());
			return a.toString();
		} catch (Throwable e) {
			return "";
		}
	}

	/** Decode the set of outstanding sends, tolerating a legacy single-object
	 *  record. Malformed individual entries are dropped, never reserved. */
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

	/** A synthetic history row for an unseen pending txid: an outgoing send at
	 *  the exact amount and fee, pending with zero confirmations, timestamped
	 *  when the send was accepted. Not built from guessed data. */
	public XmrTxInfo pendingRow(String txid) {
		return XmrTxInfo.pendingOutgoing(txid, amountAtomic, feeAtomic,
				createdAtMs / 1000L);
	}

	/**
	 * The durable outgoing history row for one of this send's txids, using the
	 * exact locally known amount and fee. When wallet2 has since observed the same
	 * txid on chain ({@code canonical} non-null) its height, confirmations, block
	 * timestamp and mined/failed state enrich the row; otherwise it is shown
	 * pending. A view-only wallet cannot reconstruct an outgoing transaction, so
	 * this local record is authoritative for direction, amount and fee and is
	 * never dropped merely because canonical history has (or has not) caught up.
	 */
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
