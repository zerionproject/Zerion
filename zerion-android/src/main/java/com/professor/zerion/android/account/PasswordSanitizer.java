package com.professor.zerion.android.account;

import java.text.Normalizer;
import java.util.Arrays;

public final class PasswordSanitizer {

	private static final char[] STRIPPED = {
			0x200B, 0x200C, 0x200D, 0x200E, 0x200F,
			0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
			0x2066, 0x2067, 0x2068, 0x2069, 0xFEFF
	};

	private PasswordSanitizer() {
	}

	public static char[] sanitize(char[] password) {
		if (password.length == 0) return new char[0];

		boolean allAscii = true;
		for (char c : password) {
			if (c > 0x7F) {
				allAscii = false;
				break;
			}
		}

		CharSequence normalized;
		if (allAscii) {
			normalized = new CharArraySequence(password);
		} else {
			normalized = Normalizer.normalize(
					new CharArraySequence(password), Normalizer.Form.NFC);
		}

		char[] result = new char[normalized.length()];
		int pos = 0;
		for (int i = 0; i < normalized.length(); i++) {
			char c = normalized.charAt(i);
			int type = Character.getType(c);

			if (type == Character.CONTROL ||
					type == Character.FORMAT ||
					type == Character.PRIVATE_USE ||
					type == Character.SURROGATE ||
					type == Character.UNASSIGNED ||
					isStripped(c)) {
				continue;
			}
			result[pos++] = c;
		}

		char[] trimmed = Arrays.copyOf(result, pos);
		Arrays.fill(result, '\0');
		return trimmed;
	}

	private static boolean isStripped(char c) {
		for (char s : STRIPPED) {
			if (c == s) return true;
		}
		return false;
	}
}
