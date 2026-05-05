package com.professor.zerion.android.sticker;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.briarproject.nullsafety.NotNullByDefault;

import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Android-Keystore-backed AES-256-GCM key for at-rest sticker encryption.
 * The key is non-extractable (StrongBox/TEE where available) and bound to
 * this app install — wipe-on-uninstall is automatic.
 *
 * Stickers themselves are stored under getFilesDir()/stickers (Android
 * FBE already encrypts that at rest), but we keep a second crypto layer
 * for parity with iOS commit 3311da4 which uses FileEncryption: a partial
 * disk dump must not see plaintext PNG bytes even with the device unlocked.
 */
@NotNullByDefault
public final class StickerKeystore {

	public static final String KEY_ALIAS = "zerion_sticker_aes_v1";
	private static final String PROVIDER = "AndroidKeyStore";

	private StickerKeystore() {
	}

	public static SecretKey getOrCreate() throws Exception {
		KeyStore ks = KeyStore.getInstance(PROVIDER);
		ks.load(null);
		KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
		if (entry instanceof KeyStore.SecretKeyEntry) {
			return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
		}
		KeyGenerator g = KeyGenerator.getInstance(
				KeyProperties.KEY_ALGORITHM_AES, PROVIDER);
		KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(KEY_ALIAS,
				KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.setRandomizedEncryptionRequired(true)
				.build();
		g.init(spec);
		return g.generateKey();
	}

	public static void deleteKey() {
		try {
			KeyStore ks = KeyStore.getInstance(PROVIDER);
			ks.load(null);
			if (ks.containsAlias(KEY_ALIAS)) {
				ks.deleteEntry(KEY_ALIAS);
			}
		} catch (Exception ignored) {
		}
	}
}
