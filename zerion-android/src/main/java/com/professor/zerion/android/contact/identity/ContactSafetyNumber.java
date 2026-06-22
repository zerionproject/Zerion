package com.professor.zerion.android.contact.identity;

import org.bouncycastle.crypto.digests.Blake2bDigest;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class ContactSafetyNumber {

	private static final byte[] LABEL =
			"ZERION_SAFETY_NUMBER_v1".getBytes(StandardCharsets.UTF_8);

	private ContactSafetyNumber() {
	}

	public static byte[] compute(byte[] localSigningPub, byte[] remoteSigningPub) {
		byte[] first;
		byte[] second;
		if (lexicographicallyPrecedes(localSigningPub, remoteSigningPub)) {
			first = localSigningPub;
			second = remoteSigningPub;
		} else {
			first = remoteSigningPub;
			second = localSigningPub;
		}
		Blake2bDigest digest = new Blake2bDigest(256);
		digest.update(LABEL, 0, LABEL.length);
		digest.update(first, 0, first.length);
		digest.update(second, 0, second.length);
		byte[] out = new byte[digest.getDigestSize()];
		digest.doFinal(out, 0);
		return out;
	}

	public static String format(byte[] digest) {
		StringBuilder sb = new StringBuilder(65);
		for (int g = 0; g < 6; g++) {
			long acc = 0;
			for (int i = 0; i < 5; i++) {
				acc = (acc << 8) | (digest[g * 5 + i] & 0xFFL);
			}
			if (g > 0) sb.append(' ');
			sb.append(String.format(Locale.US, "%010d",
					acc % 10_000_000_000L));
		}
		return sb.toString();
	}

	public static String forKeys(byte[] localSigningPub, byte[] remoteSigningPub) {
		if (localSigningPub == null || remoteSigningPub == null
				|| localSigningPub.length == 0
				|| remoteSigningPub.length == 0) {
			throw new IllegalArgumentException("Empty key");
		}
		return format(compute(localSigningPub, remoteSigningPub));
	}

	private static boolean lexicographicallyPrecedes(byte[] a, byte[] b) {
		int len = Math.min(a.length, b.length);
		for (int i = 0; i < len; i++) {
			int av = a[i] & 0xFF;
			int bv = b[i] & 0xFF;
			if (av != bv) return av < bv;
		}
		return a.length < b.length;
	}
}
