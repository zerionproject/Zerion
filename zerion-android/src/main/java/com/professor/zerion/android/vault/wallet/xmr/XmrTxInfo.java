package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

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
	public final boolean uncertain;

	private XmrTxInfo(String txid, Direction direction, long amountAtomic,
			long feeAtomic, long height, long timestamp, long confirmations,
			long unlockTime, boolean pending, boolean failed) {
		this(txid, direction, amountAtomic, feeAtomic, height, timestamp,
				confirmations, unlockTime, pending, failed, false);
	}

	private XmrTxInfo(String txid, Direction direction, long amountAtomic,
			long feeAtomic, long height, long timestamp, long confirmations,
			long unlockTime, boolean pending, boolean failed,
			boolean uncertain) {
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
		this.uncertain = uncertain;
	}

	public static XmrTxInfo pendingOutgoing(String txid, long amountAtomic,
			long feeAtomic, long timestampSec) {
		return pendingOutgoing(txid, amountAtomic, feeAtomic, timestampSec,
				false);
	}

	public static XmrTxInfo pendingOutgoing(String txid, long amountAtomic,
			long feeAtomic, long timestampSec, boolean uncertain) {
		return new XmrTxInfo(txid, Direction.OUT, amountAtomic, feeAtomic, 0,
				timestampSec, 0, 0, true, false, uncertain);
	}

	public static XmrTxInfo outgoing(String txid, long amountAtomic,
			long feeAtomic, long timestampSec, long height, long confirmations,
			boolean pending, boolean failed) {
		return new XmrTxInfo(txid, Direction.OUT, amountAtomic, feeAtomic, height,
				timestampSec, confirmations, 0, pending, failed);
	}

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
