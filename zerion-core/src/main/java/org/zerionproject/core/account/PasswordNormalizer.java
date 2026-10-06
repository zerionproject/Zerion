package org.zerionproject.core.account;

import org.briarproject.nullsafety.NotNullByDefault;

import java.text.Normalizer;
import java.util.Arrays;

import javax.annotation.Nullable;

@NotNullByDefault
public final class PasswordNormalizer {

	private static final char[] STRIPPED = {
			0x200B, 0x200C, 0x200D, 0x200E, 0x200F,
			0x202A, 0x202B, 0x202C, 0x202D, 0x202E,
			0x2066, 0x2067, 0x2068, 0x2069, 0xFEFF
	};

	private PasswordNormalizer() {
	}

	public static char[] normalize(char[] typed) {
		if (typed.length == 0) return new char[0];

		boolean allAscii = true;
		for (char c : typed) {
			if (c > 0x7F) {
				allAscii = false;
				break;
			}
		}

		CharSequence normalized;
		if (allAscii) {
			normalized = new Chars(typed);
		} else {
			normalized = Normalizer.normalize(new Chars(typed),
					Normalizer.Form.NFC);
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

	@Nullable
	public static char[] legacyForm(char[] typed, char[] normal) {
		if (Arrays.equals(typed, normal)) return null;
		return typed.clone();
	}

	private static boolean isStripped(char c) {
		for (char s : STRIPPED) {
			if (c == s) return true;
		}
		return false;
	}

	private static final class Chars implements CharSequence {

		private final char[] data;
		private final int start;
		private final int end;

		Chars(char[] data) {
			this(data, 0, data.length);
		}

		private Chars(char[] data, int start, int end) {
			this.data = data;
			this.start = start;
			this.end = end;
		}

		@Override
		public int length() {
			return end - start;
		}

		@Override
		public char charAt(int index) {
			return data[start + index];
		}

		@Override
		public CharSequence subSequence(int s, int e) {
			return new Chars(data, start + s, start + e);
		}

		@Override
		public String toString() {
			return new String(data, start, end - start);
		}
	}
}
