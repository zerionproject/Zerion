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
