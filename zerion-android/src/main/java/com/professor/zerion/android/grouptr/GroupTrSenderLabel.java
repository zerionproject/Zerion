package com.professor.zerion.android.grouptr;

import org.briarproject.nullsafety.NotNullByDefault;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import javax.annotation.Nullable;

@NotNullByDefault
final class GroupTrSenderLabel {

	static final int FINGERPRINT_BYTES = 8;

	private GroupTrSenderLabel() {
	}

	static String label(@Nullable String known, @Nullable String chosen,
			String fingerprint, String chosenFormat,
			String unverifiedFormat) {
		String alias = chosen == null ? "" : chosen.trim();
		String head;
		if (known != null && !known.isEmpty()) {
			head = alias.isEmpty() || alias.equals(known) ? known
					: String.format(Locale.ROOT, chosenFormat, known, alias);
		} else {
			head = String.format(Locale.ROOT, unverifiedFormat,
					alias.isEmpty() ? "?" : alias);
		}
		return head + " · " + fingerprint;
	}

	static String fingerprint(byte[] pubKey) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(pubKey);
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < FINGERPRINT_BYTES; i++) {
				if (i > 0 && i % 2 == 0) sb.append(' ');
				sb.append(String.format(Locale.US, "%02x", h[i]));
			}
			return sb.toString();
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}
}
