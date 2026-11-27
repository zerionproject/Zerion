package com.professor.zerion.android.sticker;

import org.briarproject.nullsafety.NotNullByDefault;

import java.security.KeyStore;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

@NotNullByDefault
public final class StickerKeystore {

	public static final String KEY_ALIAS = "zerion_sticker_aes_v1";
	private static final String PROVIDER = "AndroidKeyStore";

	private StickerKeystore() {
	}

	@Nullable
	public static SecretKey getIfExists() {
		try {
			KeyStore ks = KeyStore.getInstance(PROVIDER);
			ks.load(null);
			KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
			if (entry instanceof KeyStore.SecretKeyEntry) {
				return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
			}
			return null;
		} catch (Exception e) {
			return null;
		}
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
