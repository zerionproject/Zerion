package com.professor.zerion.android.security;

import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@NotNullByDefault
public final class TorBinaryIntegrity {

	public static final class IntegrityException extends RuntimeException {
		IntegrityException(String m) { super(m); }
	}

	private static final String KEY_PREFIX = "tor_binary_sha256_";
	private static final String KEY_VERSION_PREFIX = "tor_binary_version_";

	private TorBinaryIntegrity() {
	}

	public static void verifyOrPin(SharedPreferences prefs, File binary,
			int appVersionCode) {
		if (!binary.exists() || !binary.canRead()) return;
		String hash = sha256(binary);
		String aliasKey = KEY_PREFIX + binary.getName();
		String versionKey = KEY_VERSION_PREFIX + binary.getName();
		String storedHash = prefs.getString(aliasKey, null);
		int storedVersion = prefs.getInt(versionKey, -1);
		if (storedHash == null || storedVersion != appVersionCode) {
			prefs.edit()
					.putString(aliasKey, hash)
					.putInt(versionKey, appVersionCode)
					.apply();
			return;
		}
		if (!storedHash.equals(hash)) {
			throw new IntegrityException(
					"Tor binary hash mismatch for " + binary.getName());
		}
	}

	private static String sha256(File f) {
		try (FileInputStream in = new FileInputStream(f)) {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
			byte[] digest = md.digest();
			StringBuilder sb = new StringBuilder(digest.length * 2);
			for (byte b : digest) sb.append(String.format("%02x", b & 0xFF));
			return sb.toString();
		} catch (IOException | NoSuchAlgorithmException e) {
			throw new IntegrityException("SHA-256 hash failed: " + e);
		}
	}
}
