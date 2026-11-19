package com.professor.zerion.android.vault.util;

import com.professor.zerion.android.util.DisplayNames;
import com.professor.zerion.android.vault.model.VaultItem;

import org.briarproject.nullsafety.NotNullByDefault;

import java.net.IDN;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

@NotNullByDefault
public final class BookmarkRules {

	public static final int MAX_TITLE_LENGTH = 100;

	private BookmarkRules() {
	}

	public static String cleanTitle(@Nullable String title) {
		String clean = DisplayNames.clean(title);
		StringBuilder out = new StringBuilder(clean.length());
		boolean space = false;
		int i = 0;
		while (i < clean.length()) {
			int cp = clean.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
				space = true;
				continue;
			}
			if (space && out.length() > 0) out.append(' ');
			space = false;
			out.appendCodePoint(cp);
		}
		return out.toString();
	}

	public static boolean validTitle(String cleanedTitle) {
		return !cleanedTitle.isEmpty()
				&& cleanedTitle.length() <= MAX_TITLE_LENGTH;
	}

	@Nullable
	public static String host(@Nullable String url) {
		if (url == null) return null;
		int i = 0;
		while (i < url.length()) {
			int cp = url.codePointAt(i);
			i += Character.charCount(cp);
			if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)
					|| Character.isISOControl(cp) || hidden(cp)) {
				return null;
			}
		}
		int schemeEnd = url.indexOf("://");
		if (schemeEnd <= 0) return null;
		String scheme = url.substring(0, schemeEnd).toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) return null;
		int start = schemeEnd + 3;
		int end = start;
		while (end < url.length() && "/?#\\".indexOf(url.charAt(end)) < 0) {
			end++;
		}
		String authority = url.substring(start, end);
		if (authority.isEmpty() || authority.indexOf('@') >= 0) return null;
		String host = authority;
		if (host.startsWith("[")) {
			int close = host.indexOf(']');
			if (close < 2) return null;
			String literal = host.substring(1, close);
			if (!literal.matches("[0-9A-Fa-f:.]+")) return null;
			if (!validPort(host.substring(close + 1))) return null;
			return "[" + literal.toLowerCase(Locale.ROOT) + "]";
		}
		int port = host.lastIndexOf(':');
		if (port >= 0) {
			if (!validPort(host.substring(port))) return null;
			host = host.substring(0, port);
		}
		if (host.isEmpty() || host.indexOf('%') >= 0) return null;
		String ascii;
		try {
			ascii = IDN.toASCII(host);
		} catch (IllegalArgumentException e) {
			return null;
		}
		if (!ascii.matches("[A-Za-z0-9.-]+")) return null;
		if (ascii.startsWith(".") || ascii.endsWith(".")
				|| ascii.contains("..")) {
			return null;
		}
		return ascii.toLowerCase(Locale.ROOT);
	}

	private static boolean hidden(int cp) {
		switch (Character.getType(cp)) {
			case Character.CONTROL:
			case Character.FORMAT:
			case Character.SURROGATE:
			case Character.LINE_SEPARATOR:
			case Character.PARAGRAPH_SEPARATOR:
			case Character.UNASSIGNED:
				return true;
			default:
				return false;
		}
	}

	private static boolean validPort(String portPart) {
		if (portPart.isEmpty()) return true;
		if (portPart.charAt(0) != ':') return false;
		String digits = portPart.substring(1);
		if (digits.isEmpty() || digits.length() > 5
				|| !digits.matches("[0-9]+")) {
			return false;
		}
		return Integer.parseInt(digits) <= 65535;
	}

	public static List<VaultItem> passwords(List<VaultItem> items,
			Set<String> bookmarkIds) {
		List<VaultItem> out = new ArrayList<>();
		for (VaultItem item : items) {
			if (item.type == VaultItem.ItemType.PASSWORD
					&& !bookmarkIds.contains(item.id)) {
				out.add(item);
			}
		}
		return out;
	}

	public static List<VaultItem> bookmarks(List<VaultItem> items,
			Set<String> bookmarkIds) {
		List<VaultItem> out = new ArrayList<>();
		for (VaultItem item : items) {
			if (item.type == VaultItem.ItemType.PASSWORD
					&& bookmarkIds.contains(item.id)) {
				out.add(item);
			}
		}
		return out;
	}

	public static List<VaultItem> filterSort(List<VaultItem> bookmarks,
			Map<String, String> urls, @Nullable String query, int sortMode) {
		String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
		List<VaultItem> matches = new ArrayList<>();
		for (VaultItem item : bookmarks) {
			if (q.isEmpty()) {
				matches.add(item);
				continue;
			}
			String title = cleanTitle(item.name).toLowerCase(Locale.ROOT);
			String host = host(urls.get(item.id));
			if (title.contains(q) || (host != null && host.contains(q))) {
				matches.add(item);
			}
		}
		return VaultSearch.filterSort(matches, "", sortMode);
	}
}
