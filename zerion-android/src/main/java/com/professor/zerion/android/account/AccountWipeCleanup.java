package com.professor.zerion.android.account;

import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.vault.VaultManager;

import java.security.KeyStore;

public final class AccountWipeCleanup {

	private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

	private static final String PREFS_SECURE = "secure_prefs";
	private static final String PREFS_UI = "ui_prefs";
	private static final String PREFS_WIPE_PASSWORD = "k_wp";
	private static final String PREFS_EARLY_SUFFIX = "_preferences";

	private static final String KS_ALIAS_ANDROIDX_MASTER =
			"_androidx_security_master_key_";
	private static final String KS_ALIAS_VAULT_MASTER =
			"zerion_vault_master_key";
	private static final String KS_ALIAS_VAULT_BIOMETRIC =
			"zerion_vault_biometric_key";

	private AccountWipeCleanup() {
	}

	public static void wipe(Context context, VaultManager vaultManager) {
		Context app = context.getApplicationContext();

		wipeVaultSafe(vaultManager);

		clearSharedPrefsFile(app, PREFS_SECURE);
		clearSharedPrefsFile(app, PREFS_UI);
		clearSharedPrefsFile(app, PREFS_WIPE_PASSWORD);
		clearSharedPrefsFile(app, app.getPackageName() + PREFS_EARLY_SUFFIX);

		deleteKeyStoreEntry(KS_ALIAS_ANDROIDX_MASTER);
		deleteKeyStoreEntry(KS_ALIAS_VAULT_MASTER);
		deleteKeyStoreEntry(KS_ALIAS_VAULT_BIOMETRIC);
	}

	private static void wipeVaultSafe(VaultManager vaultManager) {
		if (vaultManager == null) return;
		try {
			vaultManager.wipeVault();
		} catch (Exception ignored) {
		}
	}

	private static void clearSharedPrefsFile(Context context, String name) {
		try {
			SharedPreferences prefs =
					context.getSharedPreferences(name, Context.MODE_PRIVATE);
			prefs.edit().clear().commit();
		} catch (Exception ignored) {
		}
	}

	private static void deleteKeyStoreEntry(String alias) {
		try {
			KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
			keyStore.load(null);
			if (keyStore.containsAlias(alias)) {
				keyStore.deleteEntry(alias);
			}
		} catch (Exception ignored) {
		}
	}
}
