package com.professor.zerion.android.vault;

import android.content.Context;
import android.content.SharedPreferences;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;

import javax.inject.Inject;
import javax.inject.Singleton;

import com.professor.zerion.android.AppModule;

@Singleton
@NotNullByDefault
public class PreferencesMigration {

	private static final String MIGRATION_COMPLETED_KEY = "vault_prefs_migration_v1_completed";
	private static final String OLD_PREFS_NAME = "vault_settings";

	private final Context context;
	private final SharedPreferences securePrefs;
	private final SharedPreferences uiPrefs;

	@Inject
	public PreferencesMigration(Context context,
			@AppModule.SecurePrefs SharedPreferences securePrefs,
			@AppModule.UiPrefs SharedPreferences uiPrefs) {
		this.context = context;
		this.securePrefs = securePrefs;
		this.uiPrefs = uiPrefs;
	}

	public void migrateVaultSettingsIfNeeded() {
		if (securePrefs.getBoolean(MIGRATION_COMPLETED_KEY, false)) {
			return;
		}

		try {
			SharedPreferences oldPrefs = context.getSharedPreferences(OLD_PREFS_NAME, Context.MODE_PRIVATE);

			if (!oldPrefs.getAll().isEmpty()) {
				migrateVaultSettings(oldPrefs);
				deleteOldPreferences(oldPrefs);
			}

			securePrefs.edit()
					.putBoolean(MIGRATION_COMPLETED_KEY, true)
					.apply();

		} catch (Exception e) {
		}
	}

	private void migrateVaultSettings(SharedPreferences oldPrefs) {
		SharedPreferences.Editor editor = securePrefs.edit();

		if (oldPrefs.contains("autolock_timeout")) {
			int autolockTimeout = oldPrefs.getInt("autolock_timeout", 60);
			editor.putInt("autolock_timeout", autolockTimeout);
		}

		if (oldPrefs.contains("biometric_enabled")) {
			boolean biometricEnabled = oldPrefs.getBoolean("biometric_enabled", false);
			editor.putBoolean("biometric_enabled", biometricEnabled);
		}

		if (oldPrefs.contains("clipboard_clear_enabled")) {
			boolean clipboardClearEnabled = oldPrefs.getBoolean("clipboard_clear_enabled", true);
			editor.putBoolean("clipboard_clear_enabled", clipboardClearEnabled);
		}

		if (oldPrefs.contains("clipboard_timeout")) {
			int clipboardTimeout = oldPrefs.getInt("clipboard_timeout", 30);
			editor.putInt("clipboard_timeout", clipboardTimeout);
		}

		if (oldPrefs.contains("hide_content_enabled")) {
			boolean hideContentEnabled = oldPrefs.getBoolean("hide_content_enabled", true);
			editor.putBoolean("hide_content_enabled", hideContentEnabled);
		}

		editor.apply();
	}

	private void deleteOldPreferences(SharedPreferences oldPrefs) {
		try {
			oldPrefs.edit().clear().apply();

			File prefsDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
			File oldPrefsFile = new File(prefsDir, OLD_PREFS_NAME + ".xml");

			if (oldPrefsFile.exists()) {
				oldPrefsFile.delete();
			}
		} catch (Exception e) {
		}
	}
}
