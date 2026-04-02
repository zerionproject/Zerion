package com.professor.zerion.android.settings;

import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.AppModule;

import org.briarproject.nullsafety.NotNullByDefault;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.annotation.Nullable;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

@NotNullByDefault
public class RegistrationLockManager {

	private static final String PREF_REG_LOCK_ENABLED = "reg_lock_enabled";
	private static final String PREF_REG_LOCK_TYPE = "reg_lock_type";
	private static final String PREF_REG_LOCK_HASH = "reg_lock_hash";
	private static final String PREF_REG_LOCK_SALT = "reg_lock_salt";

	public static final int TYPE_NONE = 0;
	public static final int TYPE_PIN = 1;
	public static final int TYPE_PASSWORD = 2;

	private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
	private static final int PBKDF2_ITERATIONS = 200000;
	private static final int PBKDF2_KEY_LENGTH = 256;
	private static final int SALT_LENGTH = 16;

	public static boolean isEnabled(Context context) {
		SharedPreferences prefs = AppModule.getAndroidComponent(context)
				.securePreferences();
		return prefs.getBoolean(PREF_REG_LOCK_ENABLED, false);
	}

	public static int getType(Context context) {
		SharedPreferences prefs = AppModule.getAndroidComponent(context)
				.securePreferences();
		return prefs.getInt(PREF_REG_LOCK_TYPE, TYPE_NONE);
	}

	public static boolean setRegistrationLock(Context context, char[] pin,
			int type) {
		byte[] salt = new byte[SALT_LENGTH];
		new SecureRandom().nextBytes(salt);

		byte[] hash = null;
		try {
			hash = hashCredential(pin, salt);
			if (hash == null) return false;

			SharedPreferences prefs = AppModule.getAndroidComponent(context)
					.securePreferences();
			prefs.edit()
					.putBoolean(PREF_REG_LOCK_ENABLED, true)
					.putInt(PREF_REG_LOCK_TYPE, type)
					.putString(PREF_REG_LOCK_HASH, bytesToHex(hash))
					.putString(PREF_REG_LOCK_SALT, bytesToHex(salt))
					.apply();
			return true;
		} finally {
			if (hash != null) Arrays.fill(hash, (byte) 0);
			Arrays.fill(salt, (byte) 0);
		}
	}

	public static boolean verify(Context context, char[] pin) {
		SharedPreferences prefs = AppModule.getAndroidComponent(context)
				.securePreferences();
		String storedHash = prefs.getString(PREF_REG_LOCK_HASH, null);
		String storedSalt = prefs.getString(PREF_REG_LOCK_SALT, null);
		if (storedHash == null || storedSalt == null) return false;

		byte[] salt = hexToBytes(storedSalt);
		byte[] expected = hexToBytes(storedHash);
		byte[] actual = null;
		try {
			actual = hashCredential(pin, salt);
			if (actual == null) return false;
			return MessageDigest.isEqual(expected, actual);
		} finally {
			if (actual != null) Arrays.fill(actual, (byte) 0);
			Arrays.fill(expected, (byte) 0);
			Arrays.fill(salt, (byte) 0);
		}
	}

	public static void disable(Context context) {
		SharedPreferences prefs = AppModule.getAndroidComponent(context)
				.securePreferences();
		prefs.edit()
				.putBoolean(PREF_REG_LOCK_ENABLED, false)
				.remove(PREF_REG_LOCK_TYPE)
				.remove(PREF_REG_LOCK_HASH)
				.remove(PREF_REG_LOCK_SALT)
				.apply();
	}

	@Nullable
	private static byte[] hashCredential(char[] credential, byte[] salt) {
		try {
			PBEKeySpec spec = new PBEKeySpec(credential, salt,
					PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH);
			SecretKeyFactory factory =
					SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
			byte[] hash = factory.generateSecret(spec).getEncoded();
			spec.clearPassword();
			return hash;
		} catch (Exception e) {
			return null;
		}
	}

	private static String bytesToHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder(bytes.length * 2);
		for (byte b : bytes) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	private static byte[] hexToBytes(String hex) {
		int len = hex.length();
		byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2) {
			data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
					+ Character.digit(hex.charAt(i + 1), 16));
		}
		return data;
	}
}
