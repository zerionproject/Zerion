package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

/**
 * One factual observation about a txid from the queried daemon. MISSED means
 * only that this daemon does not currently know the txid; it is never by
 * itself evidence that the transaction did not reach the network. Anything the
 * daemon did not answer unambiguously (transport failure, non-OK status,
 * malformed or incomplete response, malformed input) is LOOKUP_ERROR, never
 * MISSED. Interpreting these observations is the caller's policy.
 */
@NotNullByDefault
public final class XmrTxLookup {

	public enum Result { IN_POOL, MINED, MISSED, LOOKUP_ERROR }

	public static final long CODE_ERROR = -1;
	public static final long CODE_MISSED = -2;
	public static final long CODE_IN_POOL = -3;

	public final String txid;
	public final Result result;
	public final long blockHeight;

	public XmrTxLookup(String txid, Result result, long blockHeight) {
		this.txid = txid;
		this.result = result;
		this.blockHeight = blockHeight;
	}

	public static boolean isTxidHex(String s) {
		if (s.length() != 64) return false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
			if (!ok) return false;
		}
		return true;
	}

	/**
	 * Decode the native per-index codes for the requested txids. A missing or
	 * mis-sized code array yields LOOKUP_ERROR for every entry.
	 */
	public static List<XmrTxLookup> decode(List<String> requested,
			long[] codes) {
		List<XmrTxLookup> out = new ArrayList<>(requested.size());
		boolean sized = codes != null && codes.length == requested.size();
		for (int i = 0; i < requested.size(); i++) {
			String id = requested.get(i);
			long code = sized ? codes[i] : CODE_ERROR;
			if (!isTxidHex(id)) {
				out.add(new XmrTxLookup(id, Result.LOOKUP_ERROR, -1));
			} else if (code == CODE_MISSED) {
				out.add(new XmrTxLookup(id, Result.MISSED, -1));
			} else if (code == CODE_IN_POOL) {
				out.add(new XmrTxLookup(id, Result.IN_POOL, -1));
			} else if (code >= 0) {
				out.add(new XmrTxLookup(id, Result.MINED, code));
			} else {
				out.add(new XmrTxLookup(id, Result.LOOKUP_ERROR, -1));
			}
		}
		return out;
	}
}
