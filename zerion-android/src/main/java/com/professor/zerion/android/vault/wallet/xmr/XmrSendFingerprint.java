package com.professor.zerion.android.vault.wallet.xmr;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@NotNullByDefault
final class XmrSendFingerprint {

	static final int VERSION = 1;

	private static final byte[] DOMAIN =
			"ZERION:XMR:SEND:v1".getBytes(StandardCharsets.UTF_8);

	private XmrSendFingerprint() {
	}

	static int addressKindCode(MoneroEngine.AddressKind kind) {
		switch (kind) {
			case STANDARD:
				return 1;
			case SUBADDRESS:
				return 2;
			case INTEGRATED:
				return 3;
			default:
				return 0;
		}
	}

	static byte[] compute(String walletId, byte[] primaryWalletFingerprint,
			int network, String destinationExact, int addressKindCode,
			long amountAtomic, long feeAtomic, long dustAtomic,
			long totalDebitAtomic, int txCount, String[] txids) {
		ByteArrayOutputStream buf = new ByteArrayOutputStream(256);
		lengthPrefixed(buf, DOMAIN);
		u32(buf, VERSION);
		lengthPrefixed(buf, walletId.getBytes(StandardCharsets.UTF_8));
		lengthPrefixed(buf, primaryWalletFingerprint);
		u32(buf, network);
		lengthPrefixed(buf, destinationExact.getBytes(StandardCharsets.UTF_8));
		u32(buf, addressKindCode);
		u64(buf, amountAtomic);
		u64(buf, feeAtomic);
		u64(buf, dustAtomic);
		u64(buf, totalDebitAtomic);
		u64(buf, txCount);
		for (String txid : txids) {
			byte[] raw = hexToBytes(txid);
			buf.write(raw, 0, raw.length);
		}
		return sha256(buf.toByteArray());
	}

	private static void lengthPrefixed(ByteArrayOutputStream buf, byte[] value) {
		u32(buf, value.length);
		buf.write(value, 0, value.length);
	}

	private static void u32(ByteArrayOutputStream buf, int value) {
		buf.write((value >>> 24) & 0xff);
		buf.write((value >>> 16) & 0xff);
		buf.write((value >>> 8) & 0xff);
		buf.write(value & 0xff);
	}

	private static void u64(ByteArrayOutputStream buf, long value) {
		for (int shift = 56; shift >= 0; shift -= 8) {
			buf.write((int) ((value >>> shift) & 0xff));
		}
	}

	private static byte[] hexToBytes(String hex) {
		int len = hex.length();
		byte[] out = new byte[len / 2];
		for (int i = 0; i < out.length; i++) {
			int hi = Character.digit(hex.charAt(i * 2), 16);
			int lo = Character.digit(hex.charAt(i * 2 + 1), 16);
			out[i] = (byte) ((hi << 4) | lo);
		}
		return out;
	}

	private static byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
