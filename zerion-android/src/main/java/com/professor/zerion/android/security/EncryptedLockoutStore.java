package com.professor.zerion.android.security;

import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.account.LoginThrottle;

import java.io.File;

import javax.annotation.Nullable;

@NotNullByDefault
public final class EncryptedLockoutStore implements LoginThrottle.Store {

	private final SharedPreferences prefs;
	private final String key;
	private final LoginThrottle.Store plaintext;
	private final File plaintextFile;

	public EncryptedLockoutStore(SharedPreferences prefs, File plaintextFile) {
		this.prefs = prefs;
		this.plaintextFile = plaintextFile;
		this.key = plaintextFile.getAbsolutePath();
		this.plaintext = LoginThrottle.fileStore(plaintextFile);
	}

	@Override
	@Nullable
	public synchronized String load() {
		String stored = prefs.getString(key, null);
		if (stored != null && stored.isEmpty()) stored = null;
		if (!plaintextFile.exists()) return stored;
		String legacy = plaintext.load();
		if (legacy == null || legacy.isEmpty()) {
			plaintext.clear();
			return stored;
		}
		if (stored != null && !isStricter(legacy, stored)) {
			plaintext.clear();
			return stored;
		}
		if (commit(legacy)) plaintext.clear();
		return legacy;
	}

	@Override
	public synchronized void save(String state) {
		if (commit(state)) {
			if (plaintextFile.exists()) plaintext.clear();
		} else {
			plaintext.save(state);
		}
	}

	@Override
	public synchronized void clear() {
		if (prefs.contains(key)) prefs.edit().remove(key).commit();
		plaintext.clear();
	}

	private boolean commit(String state) {
		if (prefs.edit().putString(key, state).commit()) return true;
		return state.equals(prefs.getString(key, null));
	}

	static boolean isStricter(String candidate, String current) {
		long[] a = severity(candidate);
		long[] b = severity(current);
		if (a[0] != b[0]) return a[0] > b[0];
		return a[1] > b[1];
	}

	private static long[] severity(String record) {
		String[] parts = record.split(",", -1);
		if (parts.length != 5) return new long[] {-1, -1};
		try {
			return new long[] {Long.parseLong(parts[1]),
					Long.parseLong(parts[4])};
		} catch (NumberFormatException e) {
			return new long[] {-1, -1};
		}
	}
}
