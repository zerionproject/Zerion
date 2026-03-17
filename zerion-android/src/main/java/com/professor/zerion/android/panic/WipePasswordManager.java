package com.professor.zerion.android.panic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Base64;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;

import javax.annotation.Nullable;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class WipePasswordManager {

	private static final int HASH_VERSION_LEGACY = 1;
	private static final int HASH_VERSION_CURRENT = 2;

	private static final String PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256";
	private static final int PBKDF2_ITERATIONS = 200000;
	private static final int PBKDF2_KEY_LENGTH = 256;
	private static final int SALT_LENGTH = 16;

	private static final long VERIFICATION_DELAY_MS = 300;

	private static final String ENCRYPTED_PREFS_NAME = "k_wp";
	private static final String PREF_VERSION = "k_v";
	private static final String PREF_ALGORITHM = "k_a";
	private static final String PREF_ITERATIONS = "k_i";
	private static final String PREF_HASH = "k_h";
	private static final String PREF_SALT = "k_s";
	private static final String PREF_ENABLED = "k_e";
	private static final String PREF_NEEDS_MIGRATION = "k_m";

	private final SharedPreferences securePrefs;
	private final boolean secureStorageAvailable;

	private static volatile WipePasswordManager instance;

	private WipePasswordManager(Context context) throws SecurityException {
		SharedPreferences prefs = null;
		boolean secureStorage = false;

		try {
			MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
					.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
					.build();

			prefs = EncryptedSharedPreferences.create(
					context.getApplicationContext(),
					ENCRYPTED_PREFS_NAME,
					masterKey,
					EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
					EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
			);
			secureStorage = true;
		} catch (GeneralSecurityException | IOException e) {
			throw new SecurityException("", e);
		}

		this.securePrefs = prefs;
		this.secureStorageAvailable = secureStorage;

		if (secureStorage) {
			checkAndMigrateLegacyStorage(context);
		}
	}

	@Nullable
	public static WipePasswordManager getInstance(Context context) {
		if (instance == null) {
			synchronized (WipePasswordManager.class) {
				if (instance == null) {
					try {
						instance = new WipePasswordManager(context);
					} catch (SecurityException e) {
						return null;
					}
				}
			}
		}
		return instance;
	}

	public static boolean isSecureStorageAvailable(Context context) {
		try {
			MasterKey masterKey = new MasterKey.Builder(context.getApplicationContext())
					.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
					.build();

			EncryptedSharedPreferences.create(
					context.getApplicationContext(),
					ENCRYPTED_PREFS_NAME + "_test",
					masterKey,
					EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
					EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
			);
			return true;
		} catch (GeneralSecurityException | IOException e) {
			return false;
		}
	}

	public synchronized boolean isWipePasswordEnabled() {
		if (!secureStorageAvailable) return false;
		return securePrefs.getBoolean(PREF_ENABLED, false);
	}

	public synchronized boolean needsMigration() {
		if (!secureStorageAvailable) return false;
		return securePrefs.getBoolean(PREF_NEEDS_MIGRATION, false);
	}

	public synchronized boolean setWipePassword(String password) {
		enforceNonUiThread();

		if (!secureStorageAvailable) {
			return false;
		}

		if (password == null || password.trim().isEmpty()) {
			return false;
		}

		try {
			byte[] salt = new byte[SALT_LENGTH];
			new SecureRandom().nextBytes(salt);

			byte[] hash = hashPasswordPBKDF2(password, salt, PBKDF2_ITERATIONS);

			SharedPreferences.Editor editor = securePrefs.edit();
			editor.putInt(PREF_VERSION, HASH_VERSION_CURRENT);
			editor.putString(PREF_ALGORITHM, PBKDF2_ALGORITHM);
			editor.putInt(PREF_ITERATIONS, PBKDF2_ITERATIONS);
			editor.putString(PREF_HASH, Base64.encodeToString(hash, Base64.NO_WRAP));
			editor.putString(PREF_SALT, Base64.encodeToString(salt, Base64.NO_WRAP));
			editor.putBoolean(PREF_ENABLED, true);
			editor.putBoolean(PREF_NEEDS_MIGRATION, false);

			return editor.commit();
		} catch (Exception e) {
			return false;
		}
	}

	public synchronized boolean verifyWipePassword(String password) {
		enforceNonUiThread();

		long startTime = SystemClock.elapsedRealtime();

		try {
			if (!secureStorageAvailable || !isWipePasswordEnabled() ||
			    password == null || password.trim().isEmpty()) {
				return false;
			}

			int version = securePrefs.getInt(PREF_VERSION, HASH_VERSION_LEGACY);

			boolean matches;
			if (version == HASH_VERSION_CURRENT) {
				matches = verifyPBKDF2Password(password);
			} else {
				matches = verifyLegacySHA256Password(password);
				if (matches) {
					setWipePassword(password);
				}
			}

			return matches;
		} catch (Exception e) {
			return false;
		} finally {
			long elapsed = SystemClock.elapsedRealtime() - startTime;
			long remainingDelay = VERIFICATION_DELAY_MS - elapsed;
			if (remainingDelay > 0) {
				try {
					Thread.sleep(remainingDelay);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

	private boolean verifyPBKDF2Password(String password) throws Exception {
		String storedHashB64 = securePrefs.getString(PREF_HASH, null);
		String saltB64 = securePrefs.getString(PREF_SALT, null);
		int iterations = securePrefs.getInt(PREF_ITERATIONS, PBKDF2_ITERATIONS);

		if (storedHashB64 == null || saltB64 == null) {
			return false;
		}

		byte[] storedHash = Base64.decode(storedHashB64, Base64.NO_WRAP);
		byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);

		byte[] providedHash = hashPasswordPBKDF2(password, salt, iterations);

		return MessageDigest.isEqual(storedHash, providedHash);
	}

	private boolean verifyLegacySHA256Password(String password) {
		try {
			String storedHashB64 = securePrefs.getString(PREF_HASH, null);
			String saltB64 = securePrefs.getString(PREF_SALT, null);

			if (storedHashB64 == null || saltB64 == null) {
				return false;
			}

			byte[] storedHash = Base64.decode(storedHashB64, Base64.NO_WRAP);
			byte[] salt = Base64.decode(saltB64, Base64.NO_WRAP);

			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.reset();
			digest.update(salt);
			byte[] providedHash = digest.digest(password.getBytes(StandardCharsets.UTF_8));

			return MessageDigest.isEqual(storedHash, providedHash);
		} catch (NoSuchAlgorithmException e) {
			return false;
		}
	}

	public synchronized boolean removeWipePassword() {
		if (!secureStorageAvailable) return false;

		try {
			SharedPreferences.Editor editor = securePrefs.edit();
			editor.remove(PREF_VERSION);
			editor.remove(PREF_ALGORITHM);
			editor.remove(PREF_ITERATIONS);
			editor.remove(PREF_HASH);
			editor.remove(PREF_SALT);
			editor.remove(PREF_NEEDS_MIGRATION);
			editor.putBoolean(PREF_ENABLED, false);
			return editor.commit();
		} catch (Exception e) {
			return false;
		}
	}

	private byte[] hashPasswordPBKDF2(String password, byte[] salt, int iterations)
			throws NoSuchAlgorithmException, InvalidKeySpecException {
		PBEKeySpec spec = new PBEKeySpec(
				password.toCharArray(),
				salt,
				iterations,
				PBKDF2_KEY_LENGTH
		);

		SecretKeyFactory factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM);
		byte[] hash = factory.generateSecret(spec).getEncoded();

		spec.clearPassword();

		return hash;
	}

	private void enforceNonUiThread() {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			throw new IllegalStateException("");
		}
	}

	private void checkAndMigrateLegacyStorage(Context context) {
		try {
			SharedPreferences oldPrefs = androidx.preference.PreferenceManager
					.getDefaultSharedPreferences(context.getApplicationContext());

			boolean hasLegacyData = oldPrefs.contains("wipe_password_hash");

			if (hasLegacyData && !isWipePasswordEnabled()) {
				String legacyHash = oldPrefs.getString("wipe_password_hash", null);
				String legacySalt = oldPrefs.getString("wipe_password_salt", null);
				boolean legacyEnabled = oldPrefs.getBoolean("wipe_password_enabled", false);

				if (legacyHash != null && legacySalt != null && legacyEnabled) {
					SharedPreferences.Editor editor = securePrefs.edit();
					editor.putInt(PREF_VERSION, HASH_VERSION_LEGACY);
					editor.putString(PREF_ALGORITHM, "SHA-256");
					editor.putString(PREF_HASH, legacyHash);
					editor.putString(PREF_SALT, legacySalt);
					editor.putBoolean(PREF_ENABLED, true);
					editor.putBoolean(PREF_NEEDS_MIGRATION, true);
					editor.commit();

					oldPrefs.edit()
							.remove("wipe_password_hash")
							.remove("wipe_password_salt")
							.remove("wipe_password_enabled")
							.apply();
				}
			}
		} catch (Exception e) {
		}
	}

	@Nullable
	synchronized Integer getHashVersion() {
		if (!secureStorageAvailable || !isWipePasswordEnabled()) return null;
		return securePrefs.getInt(PREF_VERSION, HASH_VERSION_LEGACY);
	}

	public boolean isSecureStorageAvailable() {
		return secureStorageAvailable;
	}

	static synchronized void clearInstance() {
		instance = null;
	}
}
