package com.professor.zerion.android.vault.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class NewPassword {

	public static final String CANNOT_BE_TYPED =
			"New password holds a character that cannot be typed";

	private NewPassword() {
	}

	public static void requireTypable(char[] newPassword) {
		for (char c : newPassword) {
			if (c == '\0') {
				throw new IllegalArgumentException(CANNOT_BE_TYPED);
			}
		}
	}
}
