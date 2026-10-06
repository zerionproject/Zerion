package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@NotNullByDefault
public final class XmrSpendReconciler {

	public enum Outcome { RESOLVED, REMAIN_QUARANTINED }

	private XmrSpendReconciler() {
	}

	public static Set<String> acceptedFrom(List<XmrTxLookup> lookups,
			Set<String> outgoingHistoryTxids) {
		Set<String> accepted = new HashSet<>(outgoingHistoryTxids);
		for (XmrTxLookup l : lookups) {
			if (l.result == XmrTxLookup.Result.IN_POOL
					|| l.result == XmrTxLookup.Result.MINED) {
				accepted.add(l.txid);
			}
		}
		return accepted;
	}

	public static final long RELAY_EXPIRY_MS = 3L * 24 * 60 * 60 * 1000;

	public static boolean releasable(XmrSpendJournal journal,
			List<XmrTxLookup> lookups, Set<String> outgoingHistoryTxids,
			long nowMs, long expiryMs) {
		return releasableTxids(journal.txids(), journal.createdAtMs(), lookups,
				outgoingHistoryTxids, nowMs, expiryMs);
	}

	public static boolean releasableTxids(List<String> txids, long createdAtMs,
			List<XmrTxLookup> lookups, Set<String> outgoingHistoryTxids,
			long nowMs, long expiryMs) {
		if (txids.isEmpty()) return false;
		if (nowMs - createdAtMs < expiryMs) return false;
		java.util.Map<String, XmrTxLookup.Result> answers =
				new java.util.HashMap<>();
		for (XmrTxLookup l : lookups) answers.put(l.txid, l.result);
		for (String txid : txids) {
			if (outgoingHistoryTxids.contains(txid)) return false;
			if (answers.get(txid) != XmrTxLookup.Result.MISSED) return false;
		}
		return true;
	}

	public static Outcome decide(XmrSpendJournal journal,
			Set<String> acceptedTxids) {
		Set<String> rejected = new HashSet<>(journal.rejectedTxids());
		for (String txid : journal.txids()) {
			if (acceptedTxids.contains(txid)) continue;
			if (rejected.contains(txid)) continue;
			return Outcome.REMAIN_QUARANTINED;
		}
		return Outcome.RESOLVED;
	}
}
