package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Immutable, validated snapshot of one wallet transaction, copied out of the
 * native history at read time. Carries only what the read-only P2 UI needs; it
 * holds no native handle and no key material (history is public chain data the
 * daemon already knows). Amounts are atomic (piconero). Constructed only through
 * {@link #parse}, which rejects malformed rows so hostile daemon data cannot
 * produce a bad object.
 */
@NotNullByDefault
public final class XmrTxInfo {

	public enum Direction { IN, OUT }

	public final String txid;
	public final Direction direction;
	public final long amountAtomic;
	public final long feeAtomic;
	public final long height;
	public final long timestamp;
	public final long confirmations;
	public final long unlockTime;
	public final boolean pending;
	public final boolean failed;

	private XmrTxInfo(String txid, Direction direction, long amountAtomic,
			long feeAtomic, long height, long timestamp, long confirmations,
			long unlockTime, boolean pending, boolean failed) {
		this.txid = txid;
		this.direction = direction;
		this.amountAtomic = amountAtomic;
		this.feeAtomic = feeAtomic;
		this.height = height;
		this.timestamp = timestamp;
		this.confirmations = confirmations;
		this.unlockTime = unlockTime;
		this.pending = pending;
		this.failed = failed;
	}

	/**
	 * A synthetic pending outgoing row for the durable send overlay: an
	 * already-relayed transaction wallet2's own history has not yet observed.
	 * Built from the exact signed amount and final fee, never guessed; pending,
	 * not failed, zero confirmations, height 0.
	 */
	public static XmrTxInfo pendingOutgoing(String txid, long amountAtomic,
			long feeAtomic, long timestampSec) {
		return new XmrTxInfo(txid, Direction.OUT, amountAtomic, feeAtomic, 0,
				timestampSec, 0, 0, true, false);
	}

	/**
	 * A durable outgoing row built from the exact locally known send facts
	 * (amount, fee, timestamp) enriched with whatever canonical chain state the
	 * wallet has since proven for the same txid (height, confirmations, mined or
	 * failed state). A view-only wallet cannot itself reconstruct an outgoing
	 * transaction, so these facts come from Zerion's own record of the send, not
	 * guessed.
	 */
	public static XmrTxInfo outgoing(String txid, long amountAtomic,
			long feeAtomic, long timestampSec, long height, long confirmations,
			boolean pending, boolean failed) {
		return new XmrTxInfo(txid, Direction.OUT, amountAtomic, feeAtomic, height,
				timestampSec, confirmations, 0, pending, failed);
	}

	/**
	 * Parse one native snapshot line
	 * {@code hash,direction,amount,fee,height,timestamp,confirmations,unlockTime,pending,failed}.
	 * Returns null (dropped by the caller) if any field is missing, non-numeric,
	 * out of range, or the txid is not 64 lowercase hex characters.
	 */
	@Nullable
	public static XmrTxInfo parse(String line) {
		String[] f = line.split(",", -1);
		if (f.length < 10) return null;
		String txid = f[0];
		if (txid.length() != 64 || !isHex(txid)) return null;
		try {
			int dir = Integer.parseInt(f[1]);
			if (dir != 0 && dir != 1) return null;
			long amount = parseUnsigned(f[2]);
			long fee = parseUnsigned(f[3]);
			long height = parseUnsigned(f[4]);
			long ts = Long.parseLong(f[5]);
			long conf = parseUnsigned(f[6]);
			long unlock = parseUnsigned(f[7]);
			if (ts < 0) return null;
			boolean pending = flag(f[8]);
			boolean failed = flag(f[9]);
			return new XmrTxInfo(txid,
					dir == 0 ? Direction.IN : Direction.OUT,
					amount, fee, height, ts, conf, unlock, pending, failed);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * Parse a wallet2 unsigned-64 field. Uses {@link Long#parseUnsignedLong} so
	 * a legitimate value above {@code Long.MAX_VALUE} (notably a sender-chosen
	 * {@code unlock_time} up to 2^64-1 on a received transaction) is kept as its
	 * raw bit pattern instead of throwing and silently dropping the whole row.
	 */
	private static long parseUnsigned(String s) {
		return Long.parseUnsignedLong(s);
	}

	private static boolean flag(String s) throws NumberFormatException {
		if (s.equals("0")) return false;
		if (s.equals("1")) return true;
		throw new NumberFormatException("flag");
	}

	private static boolean isHex(String s) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
			if (!ok) return false;
		}
		return true;
	}
}
