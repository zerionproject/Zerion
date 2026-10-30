package com.professor.zerion.android.util;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.core.text.BidiFormatter;

@NotNullByDefault
public final class DisplayNames {

	public static final String PLACEHOLDER = "?";

	private static final int JOINER = 0x200D;
	private static final int NON_JOINER = 0x200C;
	private static final int BLACK_FLAG = 0x1F3F4;

	private DisplayNames() {
	}

	public static String clean(@Nullable String name) {
		if (name == null) return "";
		StringBuilder out = new StringBuilder(name.length());
		int i = 0;
		while (i < name.length()) {
			int cp = name.codePointAt(i);
			i += Character.charCount(cp);
			if (cp == JOINER || cp == NON_JOINER) {
				if (betweenTwoVisible(out, name, i)) out.appendCodePoint(cp);
			} else if (isTag(cp)) {
				if (followsFlagOrTag(out)) out.appendCodePoint(cp);
			} else if (!hidden(cp)) {
				out.appendCodePoint(cp);
			}
		}
		return out.length() == name.length() ? name : out.toString();
	}

	public static String isolate(@Nullable String name) {
		return BidiFormatter.getInstance().unicodeWrap(clean(name));
	}

	static boolean hidden(int cp) {
		switch (Character.getType(cp)) {
			case Character.CONTROL:
			case Character.FORMAT:
			case Character.SURROGATE:
			case Character.LINE_SEPARATOR:
			case Character.PARAGRAPH_SEPARATOR:
				return true;
			case Character.PRIVATE_USE:
				return cp > 0xFFFF;
			default:
				return false;
		}
	}

	private static boolean visible(int cp) {
		return !hidden(cp) && !isTag(cp) && cp != JOINER && cp != NON_JOINER
				&& !Character.isWhitespace(cp) && !Character.isSpaceChar(cp);
	}

	private static boolean betweenTwoVisible(StringBuilder out, String s,
			int next) {
		if (out.length() == 0 || next >= s.length()) return false;
		return visible(Character.codePointBefore(out, out.length()))
				&& visible(s.codePointAt(next));
	}

	private static boolean isTag(int cp) {
		return cp >= 0xE0020 && cp <= 0xE007F;
	}

	private static boolean followsFlagOrTag(StringBuilder out) {
		if (out.length() == 0) return false;
		int last = Character.codePointBefore(out, out.length());
		return last == BLACK_FLAG || isTag(last);
	}
}
