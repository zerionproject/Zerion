package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable record of a reviewed, signed Monero send. It is built once, from a
 * successfully prepared {@link MoneroEngine.Prepared} plus the exact
 * already-validated inputs that produced it, and can never change afterwards.
 * It holds no native pointer and no process-local value; the native object's
 * identity and lifetime live in {@link XmrSendOwnership}. The fingerprint over
 * these fields is what an authorization is bound to and what is recomputed from
 * the live native object immediately before relay.
 *
 * <p>Construction fails closed: any inconsistent or out-of-range native value,
 * a count that disagrees with the enumerated txids, a malformed txid, an
 * overflowing total, or a fee below the dust it already contains, throws
 * {@link XmrError.XmrException} with {@link XmrError#SEND_SNAPSHOT_INVALID}, and
 * the owning flow disposes the prepared transaction. Dust is reported for the
 * review only and is never added to the debit; the total debit is exactly
 * amount + fee.
 */
@NotNullByDefault
public final class XmrSendSnapshot {

	public static final int NETWORK_MAINNET = 0;

	private static final long MAX_TX_COUNT = 256;

	private final String walletId;
	private final byte[] primaryWalletFingerprint;
	private final int network;
	private final String destinationExact;
	private final MoneroEngine.AddressKind destinationKind;
	private final long amountAtomic;
	private final long feeAtomic;
	private final long dustAtomic;
	private final long totalDebitAtomic;
	private final int txCount;
	private final String[] allTxids;
	private final byte[] fingerprint;

	private XmrSendSnapshot(String walletId, byte[] primaryWalletFingerprint,
			int network, String destinationExact,
			MoneroEngine.AddressKind destinationKind, long amountAtomic,
			long feeAtomic, long dustAtomic, long totalDebitAtomic, int txCount,
			String[] allTxids, byte[] fingerprint) {
		this.walletId = walletId;
		this.primaryWalletFingerprint = primaryWalletFingerprint;
		this.network = network;
		this.destinationExact = destinationExact;
		this.destinationKind = destinationKind;
		this.amountAtomic = amountAtomic;
		this.feeAtomic = feeAtomic;
		this.dustAtomic = dustAtomic;
		this.totalDebitAtomic = totalDebitAtomic;
		this.txCount = txCount;
		this.allTxids = allTxids;
		this.fingerprint = fingerprint;
	}

	/**
	 * Build the snapshot from a prepared transaction and the validated inputs
	 * that produced it. The native amount, fee, dust, count and txids are read
	 * here and validated; the caller has already validated the destination and
	 * resolved its kind through Monero's parser.
	 */
	public static XmrSendSnapshot fromPrepared(String walletId,
			byte[] primaryWalletFingerprint, int network,
			String destinationExact, MoneroEngine.AddressKind destinationKind,
			MoneroEngine.Prepared prepared) throws XmrError.XmrException {
		return create(walletId, primaryWalletFingerprint, network,
				destinationExact, destinationKind, prepared.amountAtomic(),
				prepared.feeAtomic(), prepared.dustAtomic(),
				prepared.txCount(), prepared.txIds());
	}

	/**
	 * Build the snapshot from explicit already-read values. Used by
	 * {@link #fromPrepared} and directly by the final pre-relay revalidation,
	 * which re-reads the same native object and rebuilds the fingerprint to
	 * compare it against the authorized one.
	 */
	public static XmrSendSnapshot create(String walletId,
			byte[] primaryWalletFingerprint, int network,
			String destinationExact, MoneroEngine.AddressKind destinationKind,
			long amountAtomic, long feeAtomic, long dustAtomic, long txCountRaw,
			List<String> txids) throws XmrError.XmrException {
		if (walletId.isEmpty()) throw invalid();
		if (primaryWalletFingerprint.length != 32) throw invalid();
		if (network != NETWORK_MAINNET) throw invalid();
		if (destinationExact.isEmpty()) throw invalid();
		if (destinationKind == MoneroEngine.AddressKind.INVALID) throw invalid();
		if (amountAtomic <= 0) throw invalid();
		if (feeAtomic < 0 || dustAtomic < 0) throw invalid();
		if (feeAtomic < dustAtomic) throw invalid();
		if (txCountRaw < 1 || txCountRaw > MAX_TX_COUNT) throw invalid();
		if (txids.size() != txCountRaw) throw invalid();

		long totalDebit;
		try {
			totalDebit = Math.addExact(amountAtomic, feeAtomic);
		} catch (ArithmeticException overflow) {
			throw new XmrError.XmrException(XmrError.SEND_SNAPSHOT_INVALID,
					overflow);
		}

		int count = (int) txCountRaw;
		String[] copy = new String[count];
		for (int i = 0; i < count; i++) {
			String id = txids.get(i);
			if (!XmrTxLookup.isTxidHex(id)) throw invalid();
			copy[i] = id;
		}

		byte[] fpCopy = primaryWalletFingerprint.clone();
		byte[] fingerprint = XmrSendFingerprint.compute(walletId, fpCopy, network,
				destinationExact,
				XmrSendFingerprint.addressKindCode(destinationKind), amountAtomic,
				feeAtomic, dustAtomic, totalDebit, count, copy);
		return new XmrSendSnapshot(walletId, fpCopy, network, destinationExact,
				destinationKind, amountAtomic, feeAtomic, dustAtomic, totalDebit,
				count, copy, fingerprint);
	}

	private static XmrError.XmrException invalid() {
		return new XmrError.XmrException(XmrError.SEND_SNAPSHOT_INVALID);
	}

	public String walletId() {
		return walletId;
	}

	public byte[] primaryWalletFingerprint() {
		return primaryWalletFingerprint.clone();
	}

	public int network() {
		return network;
	}

	public String destinationExact() {
		return destinationExact;
	}

	public MoneroEngine.AddressKind destinationKind() {
		return destinationKind;
	}

	public long amountAtomic() {
		return amountAtomic;
	}

	public long feeAtomic() {
		return feeAtomic;
	}

	public long dustAtomic() {
		return dustAtomic;
	}

	public long totalDebitAtomic() {
		return totalDebitAtomic;
	}

	public int txCount() {
		return txCount;
	}

	public List<String> txids() {
		return new ArrayList<>(java.util.Arrays.asList(allTxids));
	}

	/** The authorization fingerprint (a fresh 32-byte copy). */
	public byte[] fingerprint() {
		return fingerprint.clone();
	}

	/** Constant-time comparison of this snapshot's fingerprint with another. */
	public boolean fingerprintEquals(byte[] other) {
		return java.security.MessageDigest.isEqual(fingerprint, other);
	}
}
