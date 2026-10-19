package com.professor.zerion.android.util;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;

@NotNullByDefault
public final class SafeFileNames {

	public static final int MAX_LENGTH = 64;
	static final int MAX_UTF8_BYTES = 200;
	static final String LAST_RESORT = "file";

	private static final int MAX_EXTENSION = 8;
	private static final String RESERVED_CHARACTERS =
			"<>:\"/\\|?*\u2044\u2215\u29F8\u29F9\uFF0F\uFF3C\uFE68\uFF1A";
	private static final Set<String> RESERVED_NAMES = new HashSet<>(
			Arrays.asList("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
					"COM0", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6",
					"COM7", "COM8", "COM9", "COM\u00B9", "COM\u00B2",
					"COM\u00B3", "LPT0", "LPT1", "LPT2", "LPT3", "LPT4",
					"LPT5", "LPT6", "LPT7", "LPT8", "LPT9", "LPT\u00B9",
					"LPT\u00B2", "LPT\u00B3"));
	private static final int JOINER = 0x200D;

	private SafeFileNames() {
	}

	public static String sanitize(@Nullable String raw, String fallback) {
		String name = clean(raw);
		if (!name.isEmpty()) return name;
		String other = clean(fallback);
		return other.isEmpty() ? LAST_RESORT : other;
	}

	private static String clean(@Nullable String raw) {
		if (raw == null) return "";
		String s = Normalizer.normalize(raw, Normalizer.Form.NFC);
		s = lastSegment(s);
		s = visible(s);
		s = collapse(s);
		s = trimEnds(s);
		if (s.isEmpty()) return "";
		String base = s;
		String ext = "";
		int dot = s.lastIndexOf('.');
		if (dot > 0 && isExtension(s.substring(dot + 1))) {
			base = trimEnds(s.substring(0, dot));
			ext = "." + s.substring(dot + 1).toLowerCase(Locale.ROOT);
		}
		if (base.isEmpty()) {
			return ext.isEmpty() ? "" : ext.substring(1);
		}
		if (isReserved(base)) base = "_" + base;
		return bound(base, ext);
	}

	private static String lastSegment(String s) {
		int cut = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
		return cut >= 0 ? s.substring(cut + 1) : s;
	}

	private static String visible(String s) {
		StringBuilder out = new StringBuilder(s.length());
		int i = 0;
		while (i < s.length()) {
			int cp = s.codePointAt(i);
			i += Character.charCount(cp);
			if (isSpace(cp)) {
				out.append(' ');
			} else if (cp == JOINER) {
				if (joinsTwoEmoji(out, s, i)) out.appendCodePoint(cp);
			} else if (isHidden(cp)) {
				continue;
			} else if (RESERVED_CHARACTERS.indexOf(cp) >= 0) {
				out.append('_');
			} else {
				out.appendCodePoint(cp);
			}
		}
		return out.toString();
	}

	private static boolean joinsTwoEmoji(StringBuilder out, String s,
			int next) {
		if (out.length() == 0) return false;
		if (!isEmoji(Character.codePointBefore(out, out.length()))) {
			return false;
		}
		int j = next;
		while (j < s.length()) {
			int cp = s.codePointAt(j);
			if (cp == JOINER || !isHidden(cp)) return isEmoji(cp);
			j += Character.charCount(cp);
		}
		return false;
	}

	private static final int[] PICTOGRAPHIC = {
			0x203C, 0x203C, 0x2049, 0x2049, 0x2122, 0x2122, 0x2139, 0x2139,
			0x2194, 0x2199, 0x21A9, 0x21AA, 0x231A, 0x231B, 0x2328, 0x2328,
			0x2388, 0x2388, 0x23CF, 0x23CF, 0x23E9, 0x23F3, 0x23F8, 0x23FA,
			0x24C2, 0x24C2, 0x25AA, 0x25AB, 0x25B6, 0x25B6, 0x25C0, 0x25C0,
			0x25FB, 0x25FE, 0x2600, 0x2605, 0x2607, 0x2612, 0x2614, 0x2685,
			0x2690, 0x2705, 0x2708, 0x2712, 0x2714, 0x2714, 0x2716, 0x2716,
			0x271D, 0x271D, 0x2721, 0x2721, 0x2728, 0x2728, 0x2733, 0x2734,
			0x2744, 0x2744, 0x2747, 0x2747, 0x274C, 0x274C, 0x274E, 0x274E,
			0x2753, 0x2755, 0x2757, 0x2757, 0x2763, 0x2767, 0x2795, 0x2797,
			0x27A1, 0x27A1, 0x27B0, 0x27B0, 0x27BF, 0x27BF, 0x2934, 0x2935,
			0x2B05, 0x2B07, 0x2B1B, 0x2B1C, 0x2B50, 0x2B50, 0x2B55, 0x2B55,
			0x3030, 0x3030, 0x303D, 0x303D, 0x3297, 0x3297, 0x3299, 0x3299,
			0x1F000, 0x1F0FF, 0x1F10D, 0x1F10F, 0x1F12F, 0x1F12F,
			0x1F16C, 0x1F171, 0x1F17E, 0x1F17F, 0x1F18E, 0x1F18E,
			0x1F191, 0x1F19A, 0x1F1AD, 0x1F1E5, 0x1F201, 0x1F20F,
			0x1F21A, 0x1F21A, 0x1F22F, 0x1F22F, 0x1F232, 0x1F23A,
			0x1F23C, 0x1F23F, 0x1F249, 0x1F3FA, 0x1F400, 0x1F53D,
			0x1F546, 0x1F64F, 0x1F680, 0x1F6FF, 0x1F774, 0x1F77F,
			0x1F7D5, 0x1F7FF, 0x1F80C, 0x1F80F, 0x1F848, 0x1F84F,
			0x1F85A, 0x1F85F, 0x1F888, 0x1F88F, 0x1F8AE, 0x1F8FF,
			0x1F90C, 0x1F93A, 0x1F93C, 0x1F945, 0x1F947, 0x1FAFF,
			0x1FC00, 0x1FFFD};

	private static boolean isEmoji(int cp) {
		if (cp >= 0x1F3FB && cp <= 0x1F3FF) return true;
		int lo = 0;
		int hi = PICTOGRAPHIC.length / 2 - 1;
		while (lo <= hi) {
			int mid = (lo + hi) >>> 1;
			if (cp < PICTOGRAPHIC[2 * mid]) {
				hi = mid - 1;
			} else if (cp > PICTOGRAPHIC[2 * mid + 1]) {
				lo = mid + 1;
			} else {
				return true;
			}
		}
		return false;
	}

	private static boolean isSpace(int cp) {
		if (cp == '\t' || cp == '\n' || cp == '\r' || cp == 0x0B
				|| cp == 0x0C) {
			return true;
		}
		int type = Character.getType(cp);
		return type == Character.SPACE_SEPARATOR
				|| type == Character.LINE_SEPARATOR
				|| type == Character.PARAGRAPH_SEPARATOR;
	}

	private static boolean isHidden(int cp) {
		switch (Character.getType(cp)) {
			case Character.CONTROL:
			case Character.FORMAT:
			case Character.PRIVATE_USE:
			case Character.SURROGATE:
			case Character.UNASSIGNED:
				return true;
			default:
				break;
		}
		return (cp >= 0xFE00 && cp <= 0xFE0F)
				|| (cp >= 0xE0100 && cp <= 0xE01EF)
				|| cp == 0x034F || cp == 0x115F || cp == 0x1160
				|| cp == 0x17B4 || cp == 0x17B5 || cp == 0x180B
				|| cp == 0x180C || cp == 0x180D || cp == 0x180F
				|| cp == 0x2800 || cp == 0x3164 || cp == 0xFFA0
				|| cp == 0xFFFC || cp == 0xFFFD;
	}

	private static String collapse(String s) {
		StringBuilder out = new StringBuilder(s.length());
		char last = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if ((c == '.' || c == ' ' || c == '_') && c == last) continue;
			out.append(c);
			last = c;
		}
		return out.toString();
	}

	private static String trimEnds(String s) {
		int start = 0;
		int end = s.length();
		while (start < end && isTrimmedAtStart(s.charAt(start))) start++;
		while (end > start && isTrimmedAtEnd(s.charAt(end - 1))) end--;
		return s.substring(start, end);
	}

	private static boolean isTrimmedAtStart(char c) {
		return c == '.' || c == '-' || c == ' ';
	}

	private static boolean isTrimmedAtEnd(char c) {
		return c == '.' || c == ' ' || c == JOINER;
	}

	private static boolean isExtension(String ext) {
		if (ext.isEmpty() || ext.length() > MAX_EXTENSION) return false;
		for (int i = 0; i < ext.length(); i++) {
			char c = ext.charAt(i);
			boolean plain = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
					|| (c >= '0' && c <= '9');
			if (!plain) return false;
		}
		return true;
	}

	private static boolean isReserved(String base) {
		int dot = base.indexOf('.');
		String stem = (dot >= 0 ? base.substring(0, dot) : base).trim();
		return RESERVED_NAMES.contains(stem.toUpperCase(Locale.ROOT));
	}

	private static String bound(String base, String ext) {
		int room = MAX_LENGTH - ext.length();
		int roomBytes = MAX_UTF8_BYTES - ext.length();
		StringBuilder kept = new StringBuilder();
		int points = 0;
		int bytes = 0;
		int i = 0;
		while (i < base.length()) {
			int cp = base.codePointAt(i);
			String one = new String(Character.toChars(cp));
			int size = one.getBytes(StandardCharsets.UTF_8).length;
			if (points + 1 > room || bytes + size > roomBytes) break;
			kept.append(one);
			points++;
			bytes += size;
			i += Character.charCount(cp);
		}
		int end = kept.length();
		while (end > 0 && isTrimmedAtEnd(kept.charAt(end - 1))) end--;
		if (end == 0) return ext.isEmpty() ? "" : ext.substring(1);
		return kept.substring(0, end) + ext;
	}
}
