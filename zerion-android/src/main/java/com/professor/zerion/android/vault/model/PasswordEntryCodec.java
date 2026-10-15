package com.professor.zerion.android.vault.model;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;

import javax.annotation.Nullable;

@NotNullByDefault
public final class PasswordEntryCodec {

	private PasswordEntryCodec() {
	}

	public static byte[] encode(String title, @Nullable String username,
			@Nullable String password, @Nullable String url,
			@Nullable String notes) {
		StringBuilder json = new StringBuilder();
		json.append("{");
		json.append("\"title\":\"").append(escape(title)).append("\",");
		json.append("\"username\":\"").append(escape(username)).append("\",");
		json.append("\"password\":\"").append(escape(password)).append("\",");
		json.append("\"url\":\"").append(escape(url)).append("\",");
		json.append("\"notes\":\"").append(escape(notes)).append("\"");
		json.append("}");
		byte[] content = json.toString().getBytes(StandardCharsets.UTF_8);
		json.setLength(0);
		return content;
	}

	public static PasswordEntry decode(String json) {
		String title = extract(json, "title");
		String username = extract(json, "username");
		String password = extract(json, "password");
		String url = extract(json, "url");
		String notes = extract(json, "notes");
		return new PasswordEntry(title, username, password, url, notes);
	}

	private static String escape(@Nullable String value) {
		if (value == null) return "";
		return value.replace("\\", "\\\\")
				.replace("\"", "\\\"")
				.replace("\n", "\\n")
				.replace("\r", "\\r")
				.replace("\t", "\\t");
	}

	private static String extract(String json, String key) {
		String searchKey = "\"" + key + "\":\"";
		int start = json.indexOf(searchKey);
		if (start == -1) return "";
		StringBuilder value = new StringBuilder();
		for (int i = start + searchKey.length(); i < json.length(); i++) {
			char c = json.charAt(i);
			if (c == '"') return value.toString();
			if (c == '\\' && i + 1 < json.length()) {
				char next = json.charAt(++i);
				switch (next) {
					case 'n':
						value.append('\n');
						break;
					case 'r':
						value.append('\r');
						break;
					case 't':
						value.append('\t');
						break;
					case '"':
					case '\\':
						value.append(next);
						break;
					default:
						value.append(c).append(next);
				}
			} else {
				value.append(c);
			}
		}
		return "";
	}
}
