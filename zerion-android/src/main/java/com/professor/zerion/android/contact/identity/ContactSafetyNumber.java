package com.professor.zerion.android.contact.identity;

import org.bouncycastle.crypto.digests.Blake2bDigest;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.annotation.Nullable;

public final class ContactSafetyNumber {

	public static final int VERSION_ED25519_ONLY = 1;
	public static final int VERSION_HYBRID = 2;

	private static final byte[] LABEL =
			"ZERION_SAFETY_NUMBER_v1".getBytes(StandardCharsets.UTF_8);
	private static final byte[] LABEL_V2 =
			"ZERION_SAFETY_NUMBER_v2".getBytes(StandardCharsets.UTF_8);

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

	public static byte[] computeHybrid(byte[] localSigningPub,
			byte[] localMlDsaPub, byte[] remoteSigningPub,
			byte[] remoteMlDsaPub) {
		boolean localFirst =
				lexicographicallyPrecedes(localSigningPub, remoteSigningPub);
		Blake2bDigest digest = new Blake2bDigest(256);
		digest.update(LABEL_V2, 0, LABEL_V2.length);
		if (localFirst) {
			updateWithLength(digest, localSigningPub);
			updateWithLength(digest, localMlDsaPub);
			updateWithLength(digest, remoteSigningPub);
			updateWithLength(digest, remoteMlDsaPub);
		} else {
			updateWithLength(digest, remoteSigningPub);
			updateWithLength(digest, remoteMlDsaPub);
			updateWithLength(digest, localSigningPub);
			updateWithLength(digest, localMlDsaPub);
		}
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
		requireKey(localSigningPub);
		requireKey(remoteSigningPub);
		return format(compute(localSigningPub, remoteSigningPub));
	}

	public static String forKeys(byte[] localSigningPub,
			@Nullable byte[] localMlDsaPub, byte[] remoteSigningPub,
			@Nullable byte[] remoteMlDsaPub) {
		requireKey(localSigningPub);
		requireKey(remoteSigningPub);
		if (versionFor(localMlDsaPub, remoteMlDsaPub) == VERSION_HYBRID) {
			return format(computeHybrid(localSigningPub, localMlDsaPub,
					remoteSigningPub, remoteMlDsaPub));
		}
		return format(compute(localSigningPub, remoteSigningPub));
	}

	public static int versionFor(@Nullable byte[] localMlDsaPub,
			@Nullable byte[] remoteMlDsaPub) {
		boolean hybrid = localMlDsaPub != null && localMlDsaPub.length > 0
				&& remoteMlDsaPub != null && remoteMlDsaPub.length > 0;
		return hybrid ? VERSION_HYBRID : VERSION_ED25519_ONLY;
	}

	private static void requireKey(@Nullable byte[] key) {
		if (key == null || key.length == 0) {
			throw new IllegalArgumentException("Empty key");
		}
	}

	private static void updateWithLength(Blake2bDigest digest, byte[] b) {
		byte[] length = new byte[] {(byte) (b.length >>> 24),
				(byte) (b.length >>> 16), (byte) (b.length >>> 8),
				(byte) b.length};
		digest.update(length, 0, length.length);
		digest.update(b, 0, b.length);
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
