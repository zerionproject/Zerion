package org.briarproject.bramble.account;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.StrongBoxUnavailableException;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.annotation.Nullable;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@NotNullByDefault
final class ProfileMetadataCrypto {

	private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
	private static final String KEY_ALIAS = "zerion_profile_metadata_v1";
	private static final String TRANSFORMATION =
			KeyProperties.KEY_ALGORITHM_AES + "/"
					+ KeyProperties.BLOCK_MODE_GCM + "/"
					+ KeyProperties.ENCRYPTION_PADDING_NONE;
	private static final int GCM_TAG_BITS = 128;
	private static final int GCM_IV_BYTES = 12;

	private final SecureRandom rng = new SecureRandom();

	@Nullable
	private SecretKey loadOrCreateKey() throws GeneralSecurityException,
			IOException {
		KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
		ks.load(null);
		KeyStore.Entry entry = ks.getEntry(KEY_ALIAS, null);
		if (entry instanceof KeyStore.SecretKeyEntry) {
			return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
		}
		KeyGenerator gen = KeyGenerator.getInstance(
				KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);
		KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(
				KEY_ALIAS,
				KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.setRandomizedEncryptionRequired(true);
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
			spec.setUnlockedDeviceRequired(true);
			try {
				gen.init(spec.setIsStrongBoxBacked(true).build());
				return gen.generateKey();
			} catch (StrongBoxUnavailableException ignored) {
			}
		}
		gen.init(spec.build());
		return gen.generateKey();
	}

	void writeEncrypted(File target, String value) throws IOException {
		SecretKey key;
		try {
			key = loadOrCreateKey();
		} catch (GeneralSecurityException e) {
			throw new IOException("metadata key unavailable", e);
		}
		if (key == null) throw new IOException("metadata key unavailable");
		byte[] iv = new byte[GCM_IV_BYTES];
		rng.nextBytes(iv);
		byte[] plaintext = value.getBytes(StandardCharsets.UTF_8);
		byte[] ciphertext;
		try {
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.ENCRYPT_MODE, key,
					new GCMParameterSpec(GCM_TAG_BITS, iv));
			ciphertext = cipher.doFinal(plaintext);
		} catch (GeneralSecurityException e) {
			java.util.Arrays.fill(plaintext, (byte) 0);
			throw new IOException("encrypt failed", e);
		} finally {
			java.util.Arrays.fill(plaintext, (byte) 0);
		}
		try (FileOutputStream out = new FileOutputStream(target)) {
			out.write(iv);
			out.write(ciphertext);
			out.flush();
		}
	}

	@Nullable
	String readEncrypted(File source) {
		if (!source.exists()) return null;
		byte[] iv = new byte[GCM_IV_BYTES];
		byte[] ciphertext;
		try (FileInputStream in = new FileInputStream(source)) {
			if (in.read(iv) != GCM_IV_BYTES) return null;
			java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
			byte[] tmp = new byte[256];
			int n;
			while ((n = in.read(tmp)) > 0) buf.write(tmp, 0, n);
			ciphertext = buf.toByteArray();
		} catch (IOException e) {
			return null;
		}
		try {
			SecretKey key = loadOrCreateKey();
			if (key == null) return null;
			Cipher cipher = Cipher.getInstance(TRANSFORMATION);
			cipher.init(Cipher.DECRYPT_MODE, key,
					new GCMParameterSpec(GCM_TAG_BITS, iv));
			byte[] plaintext = cipher.doFinal(ciphertext);
			String out = new String(plaintext, StandardCharsets.UTF_8);
			java.util.Arrays.fill(plaintext, (byte) 0);
			return out;
		} catch (GeneralSecurityException | IOException e) {
			return null;
		}
	}

	void deleteKey() {
		try {
			KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
			ks.load(null);
			if (ks.containsAlias(KEY_ALIAS)) {
				ks.deleteEntry(KEY_ALIAS);
			}
		} catch (GeneralSecurityException | IOException ignored) {
		}
	}
}
