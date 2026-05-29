package com.professor.zerion.android.decoy;

import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.AppModule;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

@NotNullByDefault
public final class DecoyConfig {

	private static final String PREF_ENABLED = "pref_decoy_enabled";
	private static final String PREF_HASH = "pref_decoy_hash";
	private static final String PREF_SALT = "pref_decoy_salt";

	private static final int HASH_ITERATIONS = 120_000;
	private static final int SALT_BYTES = 16;

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
		byte[] codeBytes = toBytes(code);
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			md.update(salt);
			md.update(codeBytes);
			byte[] current = md.digest();
			for (int i = 1; i < HASH_ITERATIONS; i++) {
				md.reset();
				md.update(salt);
				md.update(current);
				current = md.digest();
			}
			return current;
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		} finally {
			Arrays.fill(codeBytes, (byte) 0);
		}
	}

	private static byte[] toBytes(char[] code) {
		java.nio.CharBuffer cb = java.nio.CharBuffer.wrap(code);
		java.nio.ByteBuffer bb =
				StandardCharsets.UTF_8.encode(cb);
		byte[] out = new byte[bb.remaining()];
		bb.get(out);
		return out;
	}

	private static String b64(byte[] data) {
		return Base64.getEncoder().withoutPadding().encodeToString(data);
	}

	private static SharedPreferences prefs(Context ctx) {
		return AppModule.getAndroidComponent(ctx).securePreferences();
	}
}
