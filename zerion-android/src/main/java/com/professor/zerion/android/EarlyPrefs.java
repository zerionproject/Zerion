package com.professor.zerion.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.StrictMode;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Map;
import java.util.Set;

@NotNullByDefault
public final class EarlyPrefs {

	private static final String FILE = "ui_prefs";
	private static final String LEGACY_MIGRATED_KEY = "_legacy_prefs_migrated_v1";

	private EarlyPrefs() {
	}

	public static SharedPreferences get(Context ctx) {
		StrictMode.ThreadPolicy old = StrictMode.getThreadPolicy();
		StrictMode.setThreadPolicy(
				new StrictMode.ThreadPolicy.Builder(old)
						.permitDiskReads()
						.permitDiskWrites()
						.permitCustomSlowCalls()
						.build());
		try {
			MasterKey masterKey = new MasterKey.Builder(
					ctx.getApplicationContext())
					.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
					.build();
			SharedPreferences encrypted = EncryptedSharedPreferences.create(
					ctx.getApplicationContext(),
					FILE,
					masterKey,
					EncryptedSharedPreferences.PrefKeyEncryptionScheme
							.AES256_SIV,
					EncryptedSharedPreferences.PrefValueEncryptionScheme
							.AES256_GCM);
			migrateLegacyIfNeeded(ctx.getApplicationContext(), encrypted);
			return encrypted;
		} catch (GeneralSecurityException | IOException e) {
			throw new RuntimeException(e);
		} finally {
			StrictMode.setThreadPolicy(old);
		}
	}

	@SuppressWarnings("unchecked")
	private static void migrateLegacyIfNeeded(Context ctx,
			SharedPreferences encrypted) {
		if (encrypted.getBoolean(LEGACY_MIGRATED_KEY, false)) return;
		String legacyName = ctx.getPackageName() + "_preferences";
		File legacyFile = new File(
				new File(ctx.getApplicationInfo().dataDir, "shared_prefs"),
				legacyName + ".xml");
		if (legacyFile.exists()) {
			SharedPreferences legacy = ctx.getSharedPreferences(legacyName,
					Context.MODE_PRIVATE);
			Map<String, ?> all = legacy.getAll();
			SharedPreferences.Editor editor = encrypted.edit();
			for (Map.Entry<String, ?> entry : all.entrySet()) {
				Object v = entry.getValue();
				String k = entry.getKey();
				if (v instanceof Boolean) {
					editor.putBoolean(k, (Boolean) v);
				} else if (v instanceof Integer) {
					editor.putInt(k, (Integer) v);
				} else if (v instanceof Long) {
					editor.putLong(k, (Long) v);
				} else if (v instanceof Float) {
					editor.putFloat(k, (Float) v);
				} else if (v instanceof String) {
					editor.putString(k, (String) v);
				} else if (v instanceof Set) {
					editor.putStringSet(k, (Set<String>) v);
				}
			}
			editor.putBoolean(LEGACY_MIGRATED_KEY, true);
			editor.commit();
			legacy.edit().clear().commit();
			secureDelete(legacyFile);
		} else {
			encrypted.edit().putBoolean(LEGACY_MIGRATED_KEY, true).apply();
		}
	}

	private static void secureDelete(File f) {
		try {
			long len = f.length();
			if (len > 0) {
				try (java.io.RandomAccessFile raf =
						new java.io.RandomAccessFile(f, "rws")) {
					byte[] zeros = new byte[(int) Math.min(len, 4096)];
					long written = 0;
					while (written < len) {
						int chunk = (int) Math.min(zeros.length,
								len - written);
						raf.write(zeros, 0, chunk);
						written += chunk;
					}
					raf.getFD().sync();
				}
			}
			f.delete();
		} catch (IOException e) {
		}
	}
}
