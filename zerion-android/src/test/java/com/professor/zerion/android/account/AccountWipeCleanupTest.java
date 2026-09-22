package com.professor.zerion.android.account;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.security.KeyStore;

import javax.crypto.KeyGenerator;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static android.content.Context.MODE_PRIVATE;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Wiping an account removes every preference file and every keystore entry
 * the app creates, and nothing else: after the wipe no preference the app
 * wrote remains readable and no key alias remains, while a preference file
 * the app does not own is untouched.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AccountWipeCleanupTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String[] PREFS = {"secure_prefs_v2", "ui_prefs_v2",
			"k_wp_v2", "early_ui_prefs_v2", "secure_prefs", "ui_prefs", "k_wp",
			"early_ui_prefs"};
	private static final String[] ALIASES = {"zerion_prefs_master_v2",
			"zerion_prefs_master_v1", "zerion_prefs_keyname_hmac_v1",
			"zerion_boot_prefs_master_v1", "_androidx_security_master_key_",
			"zerion_vault_master_key", "zerion_vault_biometric_key"};

	@Test
	public void wipeLeavesNoPreferencesOrKeystoreEntries() throws Exception {
		Context app = RuntimeEnvironment.getApplication();
		String defaultPrefs = app.getPackageName() + "_preferences";
		for (String name : PREFS) write(app, name);
		write(app, defaultPrefs);
		app.getSharedPreferences("unrelated", MODE_PRIVATE).edit()
				.putString("keep", "y").commit();
		for (String alias : ALIASES) generateAesKey(alias);
		KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
		keyStore.load(null);
		for (String alias : ALIASES) assertTrue(keyStore.containsAlias(alias));

		AccountWipeCleanup.wipe(app, null);

		for (String name : PREFS) {
			java.util.Map<String, ?> left =
					app.getSharedPreferences(name, MODE_PRIVATE).getAll();
			assertTrue(name + " still holds " + left, left.isEmpty());
		}
		assertTrue(app.getSharedPreferences(defaultPrefs, MODE_PRIVATE)
				.getAll().isEmpty());
		for (String alias : ALIASES) {
			assertFalse(alias, keyStore.containsAlias(alias));
		}
		assertEquals("y", app.getSharedPreferences("unrelated", MODE_PRIVATE)
				.getString("keep", null));
	}

	private static void write(Context app, String name) {
		assertTrue(app.getSharedPreferences(name, MODE_PRIVATE).edit()
				.putString("secret", "value").commit());
	}

	private static void generateAesKey(String alias) throws Exception {
		KeyGenerator generator = KeyGenerator.getInstance(
				KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
		generator.init(new KeyGenParameterSpec.Builder(alias,
				KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.build());
		generator.generateKey();
	}
}
