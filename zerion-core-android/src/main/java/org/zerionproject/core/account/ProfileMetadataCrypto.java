package org.zerionproject.core.account;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@NotNullByDefault
final class ProfileMetadataCrypto {

	private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
	static final String KEY_ALIAS = "zerion_profile_metadata_v3";
	static final String FALLBACK_KEY_ALIAS = "zerion_profile_metadata_v2";
	private static final String LEGACY_KEY_ALIAS =
			"zerion_profile_metadata_v1";
	private static final String TRANSFORMATION = "AES/GCM/NoPadding";
	private static final int GCM_TAG_BITS = 128;
	private static final int GCM_IV_BYTES = 12;
	private static final int MAX_FILE_BYTES = 64 * 1024;

	interface KeyProvider {

		@Nullable
		SecretKey get(String alias, boolean create)
				throws GeneralSecurityException;
	}

	static final class KeyUnavailableException extends Exception {

		KeyUnavailableException(@Nullable Throwable cause) {
			super("metadata key unavailable", cause);
		}
	}

	private static final class NotDecryptable {

		static final NotDecryptable INSTANCE = new NotDecryptable();
	}

	private final KeyProvider keys;
	private final Set<String> resealed = ConcurrentHashMap.newKeySet();

	ProfileMetadataCrypto() {
		this(new KeystoreKeys());
	}

	ProfileMetadataCrypto(KeyProvider keys) {
		this.keys = keys;
	}

	void writeEncrypted(File target, String value) throws IOException {
		byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
		try {
			LoginThrottle.writeDurably(target, sealWithAnyKey(plaintext));
		} finally {
			Arrays.fill(plaintext, (byte) 0);
		}
	}

	private byte[] sealWithAnyKey(byte[] plaintext) throws IOException {
		Exception last = null;
		for (String alias : new String[] {KEY_ALIAS, FALLBACK_KEY_ALIAS}) {
			try {
				SecretKey key = keys.get(alias, true);
				if (key == null) continue;
				return seal(key, plaintext);
			} catch (GeneralSecurityException | RuntimeException e) {
				last = e;
			}
		}
		throw new IOException("metadata key unavailable", last);
	}

	private static byte[] seal(SecretKey key, byte[] plaintext)
			throws GeneralSecurityException {
		Cipher cipher = Cipher.getInstance(TRANSFORMATION);
		cipher.init(Cipher.ENCRYPT_MODE, key);
		byte[] iv = cipher.getIV();
		byte[] ciphertext = cipher.doFinal(plaintext);
		byte[] out = new byte[iv.length + ciphertext.length];
		System.arraycopy(iv, 0, out, 0, iv.length);
		System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
		return out;
	}

	@Nullable
	String readEncrypted(File source) throws KeyUnavailableException {
		if (!source.exists()) return null;
		long len = source.length();
		if (len <= GCM_IV_BYTES || len > MAX_FILE_BYTES) return null;
		byte[] all = new byte[(int) len];
		try (FileInputStream in = new FileInputStream(source)) {
			int read = 0;
			while (read < all.length) {
				int n = in.read(all, read, all.length - read);
				if (n < 0) return null;
				read += n;
			}
		} catch (IOException e) {
			return null;
		}
		Throwable unavailable = null;
		Object first = openWith(KEY_ALIAS, all);
		if (first instanceof String) return (String) first;
		if (first instanceof Throwable) unavailable = (Throwable) first;
		Object second = openWith(FALLBACK_KEY_ALIAS, all);
		if (second instanceof String) {
			String value = (String) second;
			if (resealed.add(source.getAbsolutePath())) reseal(source, value);
			return value;
		}
		if (second instanceof Throwable && unavailable == null) {
			unavailable = (Throwable) second;
		}
		if (unavailable != null) {
			throw new KeyUnavailableException(unavailable);
		}
		return null;
	}

	private Object openWith(String alias, byte[] sealed) {
		byte[] plaintext = null;
		Cipher cipher;
		try {
			SecretKey key = keys.get(alias, false);
			if (key == null) return NotDecryptable.INSTANCE;
			cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key,
					new GCMParameterSpec(GCM_TAG_BITS, sealed, 0,
							GCM_IV_BYTES));
		} catch (GeneralSecurityException | RuntimeException e) {
			return e;
		}
		try {
			plaintext = cipher.doFinal(sealed, GCM_IV_BYTES,
					sealed.length - GCM_IV_BYTES);
			return new String(plaintext, StandardCharsets.UTF_8);
		} catch (GeneralSecurityException | RuntimeException e) {
			return NotDecryptable.INSTANCE;
		} finally {
			if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
		}
	}

	private void reseal(File target, String value) {
		byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
		try {
			SecretKey key = keys.get(KEY_ALIAS, true);
			if (key == null) return;
			LoginThrottle.writeDurably(target, seal(key, plaintext));
		} catch (GeneralSecurityException | IOException
				| RuntimeException ignored) {
		} finally {
			Arrays.fill(plaintext, (byte) 0);
		}
	}

	void deleteKey() {
		try {
			KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
			ks.load(null);
			for (String alias : new String[] {KEY_ALIAS, FALLBACK_KEY_ALIAS,
					LEGACY_KEY_ALIAS}) {
				if (ks.containsAlias(alias)) ks.deleteEntry(alias);
			}
		} catch (GeneralSecurityException | IOException
				| RuntimeException ignored) {
		}
	}

	private static final class KeystoreKeys implements KeyProvider {

		@Override
		@Nullable
		public SecretKey get(String alias, boolean create)
				throws GeneralSecurityException {
			KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
			try {
				ks.load(null);
			} catch (IOException e) {
				throw new GeneralSecurityException(e);
			}
			if (ks.containsAlias(LEGACY_KEY_ALIAS)) {
				try {
					ks.deleteEntry(LEGACY_KEY_ALIAS);
				} catch (KeyStoreException ignored) {
				}
			}
			KeyStore.Entry entry = ks.getEntry(alias, null);
			if (entry instanceof KeyStore.SecretKeyEntry) {
				return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
			}
			if (!create) return null;
			boolean deviceBound = alias.equals(KEY_ALIAS);
			Exception last = null;
			for (boolean strongBox : new boolean[] {true, false}) {
				try {
					return generate(alias, strongBox, deviceBound);
				} catch (GeneralSecurityException | RuntimeException e) {
					last = e;
				}
			}
			if (last instanceof GeneralSecurityException) {
				throw (GeneralSecurityException) last;
			}
			throw new GeneralSecurityException("Key generation failed",
					last);
		}

		private static SecretKey generate(String alias, boolean strongBox,
				boolean deviceBound) throws GeneralSecurityException {
			boolean api28 = android.os.Build.VERSION.SDK_INT
					>= android.os.Build.VERSION_CODES.P;
			if (strongBox && !api28) {
				throw new GeneralSecurityException("StrongBox needs API 28");
			}
			KeyGenerator gen = KeyGenerator.getInstance(
					KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
			KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
					alias,
					KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
					.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
					.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
					.setKeySize(256)
					.setRandomizedEncryptionRequired(true);
			if (api28) {
				if (strongBox) spec.setIsStrongBoxBacked(true);
				if (deviceBound) spec.setUnlockedDeviceRequired(true);
			}
			gen.init(spec.build());
			return gen.generateKey();
		}
	}
}
