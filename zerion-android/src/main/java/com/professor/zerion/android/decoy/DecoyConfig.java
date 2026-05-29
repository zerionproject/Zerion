package com.professor.zerion.android.decoy;

import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.vault.crypto.Argon2;

import org.briarproject.nullsafety.NotNullByDefault;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;


@NotNullByDefault
public final class DecoyConfig {

	private static final String PREF_ENABLED = "pref_decoy_enabled";
	private static final String PREF_HASH = "pref_decoy_hash";
	private static final String PREF_SALT = "pref_decoy_salt";

	private static final int SALT_BYTES = 32;
	private static final int HASH_BYTES = 32;
	private static final int ARGON2_MEMORY_KB = 64 * 1024;
	private static final int ARGON2_ITERATIONS = 3;
	private static final int ARGON2_PARALLELISM = 1;

	private DecoyConfig() {
	}

	public static boolean isEnabled(Context ctx) {
		return prefs(ctx).getBoolean(PREF_ENABLED, false);
	}

	public static boolean hasUnlockCode(Context ctx) {
		SharedPreferences p = prefs(ctx);
		return p.contains(PREF_HASH) && p.contains(PREF_SALT);
	}

	public static void setEnabled(Context ctx, boolean enabled) {
		prefs(ctx).edit().putBoolean(PREF_ENABLED, enabled).apply();
	}

	public static void setUnlockCode(Context ctx, char[] code) {
		byte[] salt = new byte[SALT_BYTES];
		new SecureRandom().nextBytes(salt);
		byte[] hash = derive(code, salt);
		prefs(ctx).edit()
				.putString(PREF_SALT, b64(salt))
				.putString(PREF_HASH, b64(hash))
				.apply();
		Arrays.fill(hash, (byte) 0);
		Arrays.fill(salt, (byte) 0);
	}

	public static void clear(Context ctx) {
		prefs(ctx).edit()
				.remove(PREF_ENABLED)
				.remove(PREF_HASH)
				.remove(PREF_SALT)
				.apply();
	}

	public static boolean verify(Context ctx, char[] candidate) {
		SharedPreferences p = prefs(ctx);
		String saltB64 = p.getString(PREF_SALT, null);
		String hashB64 = p.getString(PREF_HASH, null);
		if (saltB64 == null || hashB64 == null) return false;
		byte[] salt;
		byte[] expected;
		try {
			salt = Base64.getDecoder().decode(saltB64);
			expected = Base64.getDecoder().decode(hashB64);
		} catch (IllegalArgumentException e) {
			return false;
		}
		byte[] candidateHash = derive(candidate, salt);
		boolean ok = MessageDigest.isEqual(candidateHash, expected);
		Arrays.fill(candidateHash, (byte) 0);
		Arrays.fill(expected, (byte) 0);
		Arrays.fill(salt, (byte) 0);
		return ok;
	}

	private static byte[] derive(char[] code, byte[] salt) {
		Argon2.Argon2Params params = new Argon2.Argon2Params(
				ARGON2_MEMORY_KB, ARGON2_ITERATIONS,
				ARGON2_PARALLELISM, HASH_BYTES);
		return new Argon2().deriveKey(code, salt, params);
	}

	private static String b64(byte[] data) {
		return Base64.getEncoder().withoutPadding().encodeToString(data);
	}

	private static SharedPreferences prefs(Context ctx) {
		return AppModule.getAndroidComponent(ctx).securePreferences();
	}
}
