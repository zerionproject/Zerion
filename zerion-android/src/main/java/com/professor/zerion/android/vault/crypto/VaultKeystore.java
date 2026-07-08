package com.professor.zerion.android.vault.crypto;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateException;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;

@NotNullByDefault
public class VaultKeystore {

	private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
	private static final String VAULT_KEY_ALIAS = "zerion_vault_master_key";
	private static final String VAULT_BIOMETRIC_KEY_ALIAS = "zerion_vault_biometric_key";

	private static final int AUTH_TIMEOUT_SECONDS = 60;

	private final Context context;
	private final KeyStore keyStore;
	private final boolean hasStrongBox;
	private final boolean hasSecureLockScreen;

	public VaultKeystore(Context context) throws KeyStoreException,
			CertificateException, IOException, NoSuchAlgorithmException {
		this.context = context;
		this.keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER);
		this.keyStore.load(null);
		this.hasStrongBox = checkStrongBoxSupport();

		KeyguardManager keyguardManager = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
		this.hasSecureLockScreen = keyguardManager != null && keyguardManager.isDeviceSecure();

	}

	private boolean checkStrongBoxSupport() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
			PackageManager pm = context.getPackageManager();
			return pm.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE);
		}
		return false;
	}

	public SecretKey getOrCreateVaultKey() throws NoSuchAlgorithmException,
			NoSuchProviderException, InvalidAlgorithmParameterException,
			UnrecoverableKeyException, KeyStoreException {

		String alias = VAULT_KEY_ALIAS;

		if (keyStore.containsAlias(alias)) {
			return (SecretKey) keyStore.getKey(alias, null);
		}

		KeyGenerator keyGenerator = KeyGenerator.getInstance(
				KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);

		KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
				alias,
				KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.setRandomizedEncryptionRequired(true);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasStrongBox) {
			builder.setIsStrongBoxBacked(true);
		}

		keyGenerator.init(builder.build());
		SecretKey key = keyGenerator.generateKey();

		return key;
	}

	public SecretKey getOrCreateBiometricKey() throws NoSuchAlgorithmException,
			NoSuchProviderException, InvalidAlgorithmParameterException,
			UnrecoverableKeyException, KeyStoreException {

		if (!hasSecureLockScreen) {
			throw new IllegalStateException("Biometric authentication requires secure lock screen");
		}

		String alias = VAULT_BIOMETRIC_KEY_ALIAS;

		if (keyStore.containsAlias(alias)) {
			return (SecretKey) keyStore.getKey(alias, null);
		}

		KeyGenerator keyGenerator = KeyGenerator.getInstance(
				KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER);

		KeyGenParameterSpec.Builder builder = new KeyGenParameterSpec.Builder(
				alias,
				KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.setUserAuthenticationRequired(true)
				.setUserAuthenticationValidityDurationSeconds(-1)
				.setRandomizedEncryptionRequired(true);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			builder.setUserAuthenticationParameters(0,
					KeyProperties.AUTH_BIOMETRIC_STRONG);
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasStrongBox) {
			builder.setIsStrongBoxBacked(true);
		}

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
			builder.setInvalidatedByBiometricEnrollment(true);
		}

		keyGenerator.init(builder.build());
		SecretKey key = keyGenerator.generateKey();

		return key;
	}

	public byte[] wrapSecret(byte[] secret, SecretKey key) throws
			NoSuchPaddingException, NoSuchAlgorithmException,
			InvalidKeyException, BadPaddingException,
			IllegalBlockSizeException {

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, key);
		byte[] iv = cipher.getIV();
		byte[] ciphertext = cipher.doFinal(secret);

		byte[] result = new byte[iv.length + ciphertext.length];
		System.arraycopy(iv, 0, result, 0, iv.length);
		System.arraycopy(ciphertext, 0, result, iv.length, ciphertext.length);

		return result;
	}

	public byte[] unwrapSecret(byte[] wrapped, SecretKey key) throws
			NoSuchPaddingException, NoSuchAlgorithmException,
			InvalidKeyException, BadPaddingException,
			IllegalBlockSizeException {

		byte[] iv = new byte[12];
		byte[] ciphertext = new byte[wrapped.length - 12];
		System.arraycopy(wrapped, 0, iv, 0, 12);
		System.arraycopy(wrapped, 12, ciphertext, 0, ciphertext.length);

		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key,
					new javax.crypto.spec.GCMParameterSpec(128, iv));
			return cipher.doFinal(ciphertext);
		} catch (InvalidAlgorithmParameterException e) {
			throw new InvalidKeyException("Failed to initialize cipher", e);
		}
	}

	public boolean isKeyHardwareBacked(SecretKey key) {
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
				SecretKeyFactory factory = SecretKeyFactory.getInstance(
						key.getAlgorithm(), KEYSTORE_PROVIDER);
				KeyInfo keyInfo = (KeyInfo) factory.getKeySpec(key, KeyInfo.class);

				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
					return keyInfo.getSecurityLevel() ==
							KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
							keyInfo.getSecurityLevel() ==
									KeyProperties.SECURITY_LEVEL_STRONGBOX;
				}
				return keyInfo.isInsideSecureHardware();
			}
		} catch (Exception e) {
		}
		return false;
	}

	public void deleteVaultKeys() throws KeyStoreException {
		if (keyStore.containsAlias(VAULT_KEY_ALIAS)) {
			keyStore.deleteEntry(VAULT_KEY_ALIAS);
		}
		if (keyStore.containsAlias(VAULT_BIOMETRIC_KEY_ALIAS)) {
			keyStore.deleteEntry(VAULT_BIOMETRIC_KEY_ALIAS);
		}
	}

	public boolean hasVaultKey() throws KeyStoreException {
		return keyStore.containsAlias(VAULT_KEY_ALIAS);
	}

	public boolean hasBiometricKey() throws KeyStoreException {
		return keyStore.containsAlias(VAULT_BIOMETRIC_KEY_ALIAS);
	}
}