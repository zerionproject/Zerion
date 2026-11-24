package com.professor.zerion.android.vault.crypto;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.account.PasswordNormalizer;

import java.util.Arrays;

@NotNullByDefault
public final class NewPassword {

	public static final String CANNOT_BE_TYPED =
			"New password holds a character that cannot be typed";
	public static final String TOO_SHORT =
			"New password is too short in its normal form";

	private NewPassword() {
	}

	public static void requireTypable(char[] newPassword) {
		for (char c : newPassword) {
			if (c == '\0') {
				throw new IllegalArgumentException(CANNOT_BE_TYPED);
			}
		}
	}

	public static char[] normalForm(char[] newPassword, int minLength) {
		requireTypable(newPassword);
		char[] normal = PasswordNormalizer.normalize(newPassword);
		if (normal.length < minLength) {
			Arrays.fill(normal, '\0');
			throw new IllegalArgumentException(TOO_SHORT);
		}
		return normal;
	}
}
