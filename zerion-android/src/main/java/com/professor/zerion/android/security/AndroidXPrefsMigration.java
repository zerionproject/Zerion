package com.professor.zerion.android.security;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.File;
import java.util.Map;
import java.util.Set;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public final class AndroidXPrefsMigration {

	private AndroidXPrefsMigration() {
	}

	public static void migrateIfNeeded(Context ctx, String oldFileName,
			ZerionEncryptedPrefs target) {
		File oldXml = sharedPrefsFile(ctx, oldFileName);
		if (!oldXml.exists()) return;
		try {
			MasterKey masterKey = new MasterKey.Builder(ctx)
					.setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
					.build();
			SharedPreferences old = EncryptedSharedPreferences.create(
					ctx, oldFileName, masterKey,
					EncryptedSharedPreferences.PrefKeyEncryptionScheme
							.AES256_SIV,
					EncryptedSharedPreferences.PrefValueEncryptionScheme
							.AES256_GCM);
			Map<String, ?> all = old.getAll();
			if (all == null || all.isEmpty()) {
				deleteFile(oldXml);
				return;
			}
			SharedPreferences.Editor editor = target.edit();
			for (Map.Entry<String, ?> e : all.entrySet()) {
				String key = e.getKey();
				Object v = e.getValue();
				if (v instanceof String) {
					editor.putString(key, (String) v);
				} else if (v instanceof Boolean) {
					editor.putBoolean(key, (Boolean) v);
				} else if (v instanceof Integer) {
					editor.putInt(key, (Integer) v);
				} else if (v instanceof Long) {
					editor.putLong(key, (Long) v);
				} else if (v instanceof Float) {
					editor.putFloat(key, (Float) v);
				} else if (v instanceof Set) {
					@SuppressWarnings("unchecked")
					Set<String> ss = (Set<String>) v;
					editor.putStringSet(key, ss);
				}
			}
			if (!editor.commit()) {
				return;
			}
			old.edit().clear().commit();
			deleteFile(oldXml);
		} catch (Throwable ignored) {
		}
	}

	private static File sharedPrefsFile(Context ctx, String name) {
		File dataDir = new File(ctx.getApplicationInfo().dataDir);
		return new File(new File(dataDir, "shared_prefs"), name + ".xml");
	}

	private static void deleteFile(File f) {
		try {
			if (f.exists()) {
				if (!f.delete()) {
					f.deleteOnExit();
				}
			}
		} catch (Throwable ignored) {
		}
	}
}
