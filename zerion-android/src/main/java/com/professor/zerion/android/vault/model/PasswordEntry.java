package com.professor.zerion.android.vault.model;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.Serializable;

@NotNullByDefault
public class PasswordEntry implements Serializable {

	public final String title;
	public final String username;
	public final String password;
	public final String url;
	public final String notes;
	public final long createdAt;
	public final long modifiedAt;

	public PasswordEntry(String title, String username, String password,
			String url, String notes) {
		this.title = title;
		this.username = username;
		this.password = password;
		this.url = url;
		this.notes = notes;
		this.createdAt = System.currentTimeMillis();
		this.modifiedAt = System.currentTimeMillis();
	}

	public PasswordEntry(String title, String username, String password,
			String url, String notes, long createdAt, long modifiedAt) {
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