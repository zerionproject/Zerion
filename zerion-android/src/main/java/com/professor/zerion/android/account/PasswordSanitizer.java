package com.professor.zerion.android.account;

import org.zerionproject.core.account.PasswordNormalizer;

public final class PasswordSanitizer {

	private PasswordSanitizer() {
	}

	public static char[] sanitize(char[] password) {
		return PasswordNormalizer.normalize(password);
	}
}
