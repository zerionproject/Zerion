package com.professor.zerion.android.vault.model;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class PasswordEntry {

	public static final String KIND_PASSWORD = "";
	public static final String KIND_BOOKMARK = "bookmark";

	public final String kind;
	public final String title;
	public final String username;
	public final String password;
	public final String url;
	public final String notes;
	public final long createdAt;
	public final long modifiedAt;

	public PasswordEntry(String title, String username, String password,
			String url, String notes) {
		this(KIND_PASSWORD, title, username, password, url, notes);
	}

	public PasswordEntry(String kind, String title, String username,
			String password, String url, String notes) {
		this.kind = kind;
		this.title = title;
		this.username = username;
		this.password = password;
		this.url = url;
		this.notes = notes;
		this.createdAt = System.currentTimeMillis();
		this.modifiedAt = System.currentTimeMillis();
	}

	public static PasswordEntry bookmark(String title, String url,
			String notes) {
		return new PasswordEntry(KIND_BOOKMARK, title, "", "", url, notes);
	}

	public boolean isBookmark() {
		return KIND_BOOKMARK.equals(kind);
	}

	public PasswordEntry(String title, String username, String password,
			String url, String notes, long createdAt, long modifiedAt) {
		this.kind = KIND_PASSWORD;
		this.title = title;
		this.username = username;
		this.password = password;
		this.url = url;
		this.notes = notes;
		this.createdAt = createdAt;
		this.modifiedAt = modifiedAt;
	}

	public String serialize() {
		return title + "\n|||FIELD|||" +
				username + "\n|||FIELD|||" +
				password + "\n|||FIELD|||" +
				url + "\n|||FIELD|||" +
				notes + "\n|||FIELD|||" +
				createdAt + "\n|||FIELD|||" +
				modifiedAt;
	}

	public static PasswordEntry deserialize(String data) {
		String[] fields = data.split("\n\\|\\|\\|FIELD\\|\\|\\|");
		if (fields.length >= 7) {
			return new PasswordEntry(
				fields[0],
				fields[1],
				fields[2],
				fields[3],
				fields[4],
				Long.parseLong(fields[5]),
				Long.parseLong(fields[6])
			);
		}
		throw new IllegalArgumentException("Invalid password entry data");
	}
}