package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

@NotNullByDefault
public final class KeePassImport {

	public static final int MAX_TITLE_LENGTH = 100;
	public static final String FOLDER_PREFIX = "Folder: ";
	public static final String FOLDER_SEPARATOR = " / ";

	public static final class Password {
		public final String title;
		public final String username;
		public final String password;
		public final String url;
		public final String notes;

		Password(String title, String username, String password, String url,
				String notes) {
			this.title = title;
			this.username = username;
			this.password = password;
			this.url = url;
			this.notes = notes;
		}

		private List<String> key() {
			return Arrays.asList(title, username, password, url, notes);
		}
	}

	public static final class Result {
		public final List<Password> passwords;
		public final int skipped;

		Result(List<Password> passwords, int skipped) {
			this.passwords = passwords;
			this.skipped = skipped;
		}
	}

	private KeePassImport() {
	}

	public static Result map(KdbxReader.Document document, String untitled) {
		List<Password> out = new ArrayList<>();
		Set<List<String>> seen = new HashSet<>();
		int skipped = document.historyEntries;
		for (KdbxEntry entry : document.entries) {
			if (entry.inRecycleBin) {
				skipped++;
				continue;
			}
			Password mapped = map(entry, untitled);
			if (mapped == null) {
				skipped++;
				continue;
			}
			if (!seen.add(mapped.key())) {
				skipped++;
				continue;
			}
			out.add(mapped);
		}
		return new Result(out, skipped);
	}

	@Nullable
	static Password map(KdbxEntry entry, String untitled) {
		String title = singleLine(entry.string(KdbxEntry.KEY_TITLE));
		String username = singleLine(entry.string(KdbxEntry.KEY_USERNAME));
		String password = entry.string(KdbxEntry.KEY_PASSWORD);
		String url = singleLine(entry.string(KdbxEntry.KEY_URL));
		if (title.isEmpty() && username.isEmpty() && password.isEmpty()
				&& url.isEmpty()) {
			return null;
		}
		if (title.isEmpty()) {
			String host = host(url);
			title = host == null ? untitled : host;
		}
		if (title.length() > MAX_TITLE_LENGTH) {
			title = title.substring(0, MAX_TITLE_LENGTH).trim();
		}
		StringBuilder notes = new StringBuilder();
		if (!entry.groupPath.isEmpty()) {
			notes.append(FOLDER_PREFIX);
			boolean first = true;
			for (String name : entry.groupPath) {
				if (!first) notes.append(FOLDER_SEPARATOR);
				first = false;
				notes.append(singleLine(name));
			}
		}
		String original = entry.string(KdbxEntry.KEY_NOTES).trim();
		if (!original.isEmpty()) {
			if (notes.length() > 0) notes.append('\n');
			notes.append(original);
		}
		for (Map.Entry<String, String> field : entry.strings.entrySet()) {
			String key = field.getKey();
			if (isStandardField(key)) continue;
			String value = field.getValue();
			if (key.trim().isEmpty() && value.trim().isEmpty()) continue;
			if (notes.length() > 0) notes.append('\n');
			notes.append(singleLine(key)).append(": ").append(value);
		}
		return new Password(title, username, password, url, notes.toString());
	}

	private static boolean isStandardField(String key) {
		return KdbxEntry.KEY_TITLE.equals(key)
				|| KdbxEntry.KEY_USERNAME.equals(key)
				|| KdbxEntry.KEY_PASSWORD.equals(key)
				|| KdbxEntry.KEY_URL.equals(key)
				|| KdbxEntry.KEY_NOTES.equals(key);
	}

	private static String singleLine(String value) {
		return value.replace('\r', ' ').replace('\n', ' ').trim();
	}

	@Nullable
	static String host(String url) {
		String rest = url.trim();
		int scheme = rest.indexOf("://");
		if (scheme >= 0) rest = rest.substring(scheme + 3);
		int end = rest.length();
		for (int i = 0; i < rest.length(); i++) {
			char c = rest.charAt(i);
			if (c == '/' || c == '?' || c == '#') {
				end = i;
				break;
			}
		}
		String authority = rest.substring(0, end);
		int at = authority.lastIndexOf('@');
		if (at >= 0) authority = authority.substring(at + 1);
		if (authority.startsWith("[")) {
			int close = authority.indexOf(']');
			if (close < 0) return null;
			authority = authority.substring(0, close + 1);
		} else {
			int colon = authority.indexOf(':');
			if (colon >= 0) authority = authority.substring(0, colon);
		}
		authority = authority.trim();
		if (authority.isEmpty()) return null;
		for (int i = 0; i < authority.length(); i++) {
			if (Character.isWhitespace(authority.charAt(i))) return null;
		}
		return authority;
	}
}
