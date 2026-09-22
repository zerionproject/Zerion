package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The conservative reconciliation policy for a spend journal. A transaction is
 * resolved only by <b>positive</b> evidence that it reached the network, either
 * the daemon reporting it in its pool or a block, or the wallet's own outgoing
 * history recording it, or by a <b>definitive rejection</b> that the relay itself
 * returned. A quarantine clears only when every journal transaction is resolved
 * one of those two ways.
 *
 * <p>A MISSED answer is never negative proof: it means only that the queried
 * daemon does not currently know the txid, and a daemon can restart or lose its
 * mempool after already propagating the transaction. So MISSED, a lookup error,
 * or a transport timeout never resolves anything, no matter how many times it is
 * seen, from any node, after any number of blocks or any timeout. An ambiguous
 * relay with no positive result and no definitive rejection stays
 * quarantined.
 */
@NotNullByDefault
public final class XmrSpendReconciler {

	public enum Outcome { RESOLVED, REMAIN_QUARANTINED }

	private XmrSpendReconciler() {
	}

	/**
	 * The set of journal txids with positive evidence of reaching the network:
	 * a pool or mined lookup, or presence in the wallet's outgoing history. A
	 * MISSED or errored lookup contributes nothing, and never will.
	 */
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

	/**
	 * Decide whether the journal can be resolved. Every txid must be either
	 * positively accepted or definitively rejected; any unresolved txid keeps the
	 * whole journal quarantined.
	 */
	/**
	 * How long an unresolved relay must have been absent from the network
	 * before the user may release it. Monero relay pools drop a transaction
	 * after three days, so a transaction still MISSED after this window, on a
	 * daemon that answered, is one no node in the relay path is holding.
	 */
	public static final long RELAY_EXPIRY_MS = 3L * 24 * 60 * 60 * 1000;

	/**
	 * Whether an unresolved journal may be released by an explicit,
	 * password-gated user decision. This is not negative proof (no such proof
	 * exists for a signed transaction), which is why it is never automatic:
	 * it requires that the journal is at least {@code expiryMs} old, that the
	 * daemon answered for every journal txid and reported none of them in its
	 * pool or a block (a LOOKUP_ERROR, IN_POOL or MINED answer, or a missing
	 * answer, blocks the release), and that the wallet's own outgoing history
	 * does not contain any of them. Releasing then lets the user spend the
	 * same inputs again; if the old transaction were ever to land first, the
	 * network rejects the newer one as a double spend, so at most one of the
	 * two can ever pay out and no funds are lost.
	 */
	public static boolean releasable(XmrSpendJournal journal,
			List<XmrTxLookup> lookups, Set<String> outgoingHistoryTxids,
			long nowMs, long expiryMs) {
		if (nowMs - journal.createdAtMs() < expiryMs) return false;
		java.util.Map<String, XmrTxLookup.Result> answers =
				new java.util.HashMap<>();
		for (XmrTxLookup l : lookups) answers.put(l.txid, l.result);
		for (String txid : journal.txids()) {
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
