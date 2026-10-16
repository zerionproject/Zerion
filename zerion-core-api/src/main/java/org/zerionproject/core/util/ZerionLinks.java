package org.zerionproject.core.util;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class ZerionLinks {

	public static final String SCHEME = "zerion";

	public static final String SCHEME_PATTERN = anyAsciiCase(SCHEME);

	private static final String PREFIX = SCHEME + "://";

	private ZerionLinks() {
	}

	private static String anyAsciiCase(String lowercaseWord) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < lowercaseWord.length(); i++) {
			char c = lowercaseWord.charAt(i);
			if (c < 'a' || c > 'z') throw new IllegalArgumentException();
			sb.append('[').append(c).append((char) (c - ('a' - 'A')))
					.append(']');
		}
		return sb.toString();
	}

	public static boolean hasZerionScheme(String link) {
		if (link.length() < PREFIX.length()) return false;
		for (int i = 0; i < PREFIX.length(); i++) {
			char c = link.charAt(i);
			char want = PREFIX.charAt(i);
			if (c == want) continue;
			if (c >= 'A' && c <= 'Z' && (char) (c + ('a' - 'A')) == want) {
				continue;
			}
			return false;
		}
		return true;
	}

	public static String normalizeScheme(String link) {
		if (!hasZerionScheme(link) || link.startsWith(PREFIX)) return link;
		return PREFIX + link.substring(PREFIX.length());
	}
}
