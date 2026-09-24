package com.professor.zerion.android.account;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.professor.zerion.android.vault.VaultManager;

import java.io.File;
import java.security.KeyStore;

public final class AccountWipeCleanup {

	private static final String ANDROID_KEYSTORE = "AndroidKeyStore";

	private static final String PREFS_SECURE = "secure_prefs";
	private static final String PREFS_UI = "ui_prefs";
	private static final String PREFS_WIPE_PASSWORD = "k_wp";
	private static final String PREFS_EARLY = "early_ui_prefs";
	private static final String PREFS_EARLY_SUFFIX = "_preferences";
	private static final String PREFS_V2_SUFFIX = "_v2";

	private static final String KS_ALIAS_ZERION_PREFS_MASTER =
			"zerion_prefs_master_v2";
	private static final String KS_ALIAS_ZERION_PREFS_MASTER_LEGACY =
			"zerion_prefs_master_v1";
	private static final String KS_ALIAS_ZERION_PREFS_KEYNAME_HMAC =
			"zerion_prefs_keyname_hmac_v1";
	private static final String KS_ALIAS_ZERION_BOOT_PREFS_MASTER =
			"zerion_boot_prefs_master_v1";
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

		clearAndDeletePrefs(app, PREFS_SECURE + PREFS_V2_SUFFIX);
		clearAndDeletePrefs(app, PREFS_UI + PREFS_V2_SUFFIX);
		clearAndDeletePrefs(app, PREFS_WIPE_PASSWORD + PREFS_V2_SUFFIX);
		clearAndDeletePrefs(app, PREFS_EARLY + PREFS_V2_SUFFIX);
		clearAndDeletePrefs(app, PREFS_SECURE);
		clearAndDeletePrefs(app, PREFS_UI);
		clearAndDeletePrefs(app, PREFS_WIPE_PASSWORD);
		clearAndDeletePrefs(app, PREFS_EARLY);
		clearAndDeletePrefs(app, app.getPackageName() + PREFS_EARLY_SUFFIX);
		clearAndDeleteEveryPrefsFile(app);

		deleteKeyStoreEntry(KS_ALIAS_ZERION_PREFS_MASTER);
		deleteKeyStoreEntry(KS_ALIAS_ZERION_PREFS_MASTER_LEGACY);
		deleteKeyStoreEntry(KS_ALIAS_ZERION_PREFS_KEYNAME_HMAC);
		deleteKeyStoreEntry(KS_ALIAS_ZERION_BOOT_PREFS_MASTER);
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

	/**
	 * Nothing in the preferences directory should survive a wipe: a file
	 * that is not on the list above (a legacy plaintext file, a library's
	 * own file) would still show that the app was used and, at worst, what
	 * it was used for.
	 */
	private static void clearAndDeleteEveryPrefsFile(Context context) {
		try {
			File dir = new File(context.getApplicationInfo().dataDir,
					"shared_prefs");
			File[] files = dir.listFiles();
			if (files == null) return;
			for (File f : files) {
				String name = f.getName();
				if (name.endsWith(".xml")) {
					clearAndDeletePrefs(context,
							name.substring(0, name.length() - 4));
				}
				if (f.exists() && !f.delete()) {
					f.deleteOnExit();
				}
			}
		} catch (Exception ignored) {
		}
	}

	private static void clearAndDeletePrefs(Context context, String name) {
		try {
			SharedPreferences prefs =
					context.getSharedPreferences(name, Context.MODE_PRIVATE);
			prefs.edit().clear().commit();
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
				context.deleteSharedPreferences(name);
			}
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
