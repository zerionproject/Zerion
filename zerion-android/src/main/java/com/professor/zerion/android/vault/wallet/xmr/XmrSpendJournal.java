package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A write-ahead record that a relay was attempted for a set of signed
 * transactions, held so an uncertain relay can never be silently forgotten. It
 * persists only the minimum safety state and never a destination, amount, fee,
 * credential, token, snapshot, signed bytes, native pointer or session. It is
 * encoded in its own strict, self-contained text format so the reader can fail
 * closed on anything malformed rather than degrading to "no journal" the way the
 * general settings readers do.
 *
 * <p>Any parse problem throws {@link XmrError.XmrException} with
 * {@link XmrError#JOURNAL_CORRUPTED}; the caller treats that, like a present
 * journal, as {@link XmrError#SPEND_QUARANTINED}.
 */
@NotNullByDefault
public final class XmrSpendJournal {

	public enum State { RELAYING, UNCERTAIN }

	private static final String MAGIC = "ZSPENDQ1";
	private static final int MAX_TX = 256;
	private static final int MAX_INPUT_LEN = 64 * 1024;
	private static final int MAX_LINES = MAX_TX * 2 + 16;

	private final State state;
	private final String walletId;
	private final String primaryFingerprintHex;
	private final int txCount;
	private final String[] txids;
	private final String relayEndpointId;
	private final long createdAtMs;
	private final String[] rejectedTxids;

	private XmrSpendJournal(State state, String walletId,
			String primaryFingerprintHex, int txCount, String[] txids,
			String relayEndpointId, long createdAtMs, String[] rejectedTxids) {
		this.state = state;
		this.walletId = walletId;
		this.primaryFingerprintHex = primaryFingerprintHex;
		this.txCount = txCount;
		this.txids = txids;
		this.relayEndpointId = relayEndpointId;
		this.createdAtMs = createdAtMs;
		this.rejectedTxids = rejectedTxids;
	}

	/**
	 * Build a journal from validated send state. Rejects the same inconsistencies
	 * the parser rejects, so a journal can never be created in a form that would
	 * later read back as corrupt.
	 */
	public static XmrSpendJournal create(State state, String walletId,
			String primaryFingerprintHex, List<String> txids,
			String relayEndpointId, long createdAtMs,
			List<String> rejectedTxids) throws XmrError.XmrException {
		if (walletId.isEmpty()) throw corrupt();
		if (!isSha256Hex(primaryFingerprintHex)) throw corrupt();
		if (relayEndpointId.isEmpty()
				|| !(relayEndpointId.startsWith("tor:")
						|| relayEndpointId.startsWith("direct:"))) {
			throw corrupt();
		}
		if (createdAtMs <= 0) throw corrupt();
		if (txids.size() < 1 || txids.size() > MAX_TX) throw corrupt();
		Set<String> seen = new LinkedHashSet<>();
		for (String id : txids) {
			if (!XmrTxLookup.isTxidHex(id) || !seen.add(id)) throw corrupt();
		}
		for (String r : rejectedTxids) {
			if (!seen.contains(r)) throw corrupt();
		}
		return new XmrSpendJournal(state, walletId, primaryFingerprintHex,
				txids.size(), txids.toArray(new String[0]), relayEndpointId,
				createdAtMs, rejectedTxids.toArray(new String[0]));
	}

	public State state() {
		return state;
	}

	public String walletId() {
		return walletId;
	}

	public String primaryFingerprintHex() {
		return primaryFingerprintHex;
	}

	public int txCount() {
		return txCount;
	}

	public List<String> txids() {
		return new ArrayList<>(Arrays.asList(txids));
	}

	public String relayEndpointId() {
		return relayEndpointId;
	}

	public long createdAtMs() {
		return createdAtMs;
	}

	public List<String> rejectedTxids() {
		return new ArrayList<>(Arrays.asList(rejectedTxids));
	}

	public String serialize() {
		StringBuilder sb = new StringBuilder(128);
		sb.append(MAGIC).append('\n');
		sb.append("state=").append(state.name()).append('\n');
		sb.append("wid=").append(walletId).append('\n');
		sb.append("af=").append(primaryFingerprintHex).append('\n');
		sb.append("n=").append(txCount).append('\n');
		for (String id : txids) sb.append("tx=").append(id).append('\n');
		sb.append("ep=").append(relayEndpointId).append('\n');
		sb.append("at=").append(createdAtMs).append('\n');
		for (String r : rejectedTxids) sb.append("rej=").append(r).append('\n');
		return sb.toString();
	}

	/**
	 * Strictly parse a journal, requiring it to belong to {@code expectedWalletId}.
	 * Any deviation, an unknown version, an inconsistent count, a malformed or
	 * duplicated txid, a rejection that names an unknown txid, or an oversized
	 * input, is {@link XmrError#JOURNAL_CORRUPTED}. It never returns a partial or
	 * defaulted journal.
	 */
	public static XmrSpendJournal parse(String expectedWalletId, String text)
			throws XmrError.XmrException {
		if (text.length() > MAX_INPUT_LEN) throw corrupt();
		String[] lines = text.split("\n", -1);
		if (lines.length > MAX_LINES) throw corrupt();
		if (lines.length < 1 || !MAGIC.equals(lines[0])) throw corrupt();

		String stateStr = null;
		String wid = null;
		String af = null;
		String nStr = null;
		String ep = null;
		String atStr = null;
		List<String> txids = new ArrayList<>();
		List<String> rej = new ArrayList<>();

		for (int i = 1; i < lines.length; i++) {
			String line = lines[i];
			if (line.isEmpty() && i == lines.length - 1) continue;
			int eq = line.indexOf('=');
			if (eq <= 0) throw corrupt();
			String key = line.substring(0, eq);
			String value = line.substring(eq + 1);
			switch (key) {
				case "state":
					if (stateStr != null) throw corrupt();
					stateStr = value;
					break;
				case "wid":
					if (wid != null) throw corrupt();
					wid = value;
					break;
				case "af":
					if (af != null) throw corrupt();
					af = value;
					break;
				case "n":
					if (nStr != null) throw corrupt();
					nStr = value;
					break;
				case "tx":
					txids.add(value);
					break;
				case "ep":
					if (ep != null) throw corrupt();
					ep = value;
					break;
				case "at":
					if (atStr != null) throw corrupt();
					atStr = value;
					break;
				case "rej":
					rej.add(value);
					break;
				default:
					throw corrupt();
			}
		}

		if (stateStr == null || wid == null || af == null || nStr == null
				|| ep == null || atStr == null) {
			throw corrupt();
		}
		if (!wid.equals(expectedWalletId)) throw corrupt();

		State state;
		try {
			state = State.valueOf(stateStr);
		} catch (IllegalArgumentException e) {
			throw corrupt();
		}
		int n = parseInt(nStr);
		long at = parseLong(atStr);
		if (n != txids.size()) throw corrupt();

		return create(state, wid, af, txids, ep, at, rej);
	}

	private static int parseInt(String s) throws XmrError.XmrException {
		try {
			return Integer.parseInt(s);
		} catch (NumberFormatException e) {
			throw corrupt();
		}
	}

	private static long parseLong(String s) throws XmrError.XmrException {
		try {
			return Long.parseLong(s);
		} catch (NumberFormatException e) {
			throw corrupt();
		}
	}

	private static boolean isSha256Hex(String s) {
		if (s.length() != 64) return false;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
			if (!ok) return false;
		}
		return true;
	}

	private static XmrError.XmrException corrupt() {
		return new XmrError.XmrException(XmrError.JOURNAL_CORRUPTED);
	}
}
