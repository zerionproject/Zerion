package com.professor.zerion.android.contact.identity;

import org.bouncycastle.crypto.digests.Blake2bDigest;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class IdentityFingerprint {

	private static final byte[] LABEL =
			"ZERION_IDENTITY_FINGERPRINT_v1".getBytes(StandardCharsets.UTF_8);

	private IdentityFingerprint() {
	}

	public static byte[] compute(byte[] signingPub) {
		Blake2bDigest digest = new Blake2bDigest(256);
		digest.update(LABEL, 0, LABEL.length);
		digest.update(signingPub, 0, signingPub.length);
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

	public static String forSigningPub(byte[] signingPub) {
		if (signingPub == null || signingPub.length == 0) return "";
		return format(compute(signingPub));
	}
}
