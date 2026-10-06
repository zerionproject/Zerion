package com.professor.zerion.android.vault.crypto;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;

import com.professor.zerion.android.vault.storage.VaultLocation;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.security.GeneralSecurityException;
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

import javax.annotation.Nullable;

@NotNullByDefault
public class VaultKeystore {

	private static final String KEYSTORE_PROVIDER = "AndroidKeyStore";
	public static final String LEGACY_KEY_ALIAS = "zerion_vault_master_key";
	private static final String PROFILE_KEY_ALIAS_PREFIX =
			LEGACY_KEY_ALIAS + "_";
	private static final String VAULT_BIOMETRIC_KEY_ALIAS = "zerion_vault_biometric_key";

	private static final int AUTH_TIMEOUT_SECONDS = 60;

	private final Context context;
	private final KeyStore keyStore;
	private final boolean hasStrongBox;
	private final boolean hasSecureLockScreen;
	@Nullable
	private final VaultLocation location;

	public VaultKeystore(Context context) throws KeyStoreException,
			CertificateException, IOException, NoSuchAlgorithmException {
		this(context, null);
	}

	public VaultKeystore(Context context, @Nullable VaultLocation location)
			throws KeyStoreException, CertificateException, IOException,
			NoSuchAlgorithmException {
		this.context = context;
		this.location = location;
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

		String alias = alias();

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

		boolean useStrongBox =
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasStrongBox;
		if (useStrongBox) {
			builder.setIsStrongBoxBacked(true);
		}

		try {
			keyGenerator.init(builder.build());
			return keyGenerator.generateKey();
		} catch (Exception e) {
			if (!useStrongBox) {
				throw e;
			}
		}

		KeyGenParameterSpec.Builder fallback = new KeyGenParameterSpec.Builder(
				alias,
				KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.setRandomizedEncryptionRequired(true);
		keyGenerator.init(fallback.build());
		return keyGenerator.generateKey();
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

	private String alias() {
		return location == null ? LEGACY_KEY_ALIAS : location.keyAlias();
	}

	public static final int SLOT_A = 1;
	public static final int SLOT_B = 2;

	public static int otherSlot(int slot) {
		return slot == SLOT_A ? SLOT_B : SLOT_A;
	}

	private String wrapAlias(int slot) {
		return alias() + (slot == SLOT_A ? "_a" : "_b");
	}

	private String hmacAlias(int slot) {
		return alias() + (slot == SLOT_A ? "_ha" : "_hb");
	}

	public void createSlot(int slot) throws GeneralSecurityException {
		deleteSlot(slot);
		try {
			generate(wrapAlias(slot), KeyProperties.KEY_ALGORITHM_AES,
					KeyProperties.PURPOSE_ENCRYPT
							| KeyProperties.PURPOSE_DECRYPT, true);
			generate(hmacAlias(slot), KeyProperties.KEY_ALGORITHM_HMAC_SHA256,
					KeyProperties.PURPOSE_SIGN, false);
		} catch (GeneralSecurityException | RuntimeException e) {
			deleteSlot(slot);
			throw e instanceof GeneralSecurityException
					? (GeneralSecurityException) e
					: new GeneralSecurityException(e);
		}
	}

	private void generate(String alias, String algorithm, int purposes,
			boolean gcm) throws GeneralSecurityException {
		KeyGenerator generator = KeyGenerator.getInstance(algorithm,
				KEYSTORE_PROVIDER);
		boolean useStrongBox =
				Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasStrongBox;
		for (int attempt = useStrongBox ? 0 : 1; attempt < 2; attempt++) {
			KeyGenParameterSpec.Builder builder =
					new KeyGenParameterSpec.Builder(alias, purposes)
							.setKeySize(256);
			if (gcm) {
				builder.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
						.setEncryptionPaddings(
								KeyProperties.ENCRYPTION_PADDING_NONE)
						.setRandomizedEncryptionRequired(true);
			}
			if (attempt == 0) builder.setIsStrongBoxBacked(true);
			try {
				generator.init(builder.build());
				generator.generateKey();
				return;
			} catch (GeneralSecurityException | RuntimeException e) {
				if (attempt == 1) throw e;
			}
		}
	}

	@Nullable
	public SecretKey wrapKey(int slot) throws GeneralSecurityException {
		return (SecretKey) keyStore.getKey(wrapAlias(slot), null);
	}

	public byte[] hmac(int slot, byte[] input)
			throws GeneralSecurityException {
		SecretKey key = (SecretKey) keyStore.getKey(hmacAlias(slot), null);
		if (key == null) {
			throw new GeneralSecurityException("Vault key slot missing");
		}
		javax.crypto.Mac mac = javax.crypto.Mac.getInstance(
				KeyProperties.KEY_ALGORITHM_HMAC_SHA256);
		mac.init(key);
		return mac.doFinal(input);
	}

	public void deleteSlot(int slot) {
		deleteQuietly(wrapAlias(slot));
		deleteQuietly(hmacAlias(slot));
	}

	public void deleteVersionOneKey() {
		deleteQuietly(alias());
	}

	private void deleteQuietly(String alias) {
		try {
			if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias);
		} catch (KeyStoreException ignored) {
		}
	}

	public static String profileKeyAlias(String profileTag) {
		return PROFILE_KEY_ALIAS_PREFIX + profileTag;
	}

	public void deleteVaultKeys() throws KeyStoreException {
		String alias = alias();
		if (keyStore.containsAlias(alias)) {
			keyStore.deleteEntry(alias);
		}
		deleteSlot(SLOT_A);
		deleteSlot(SLOT_B);
		if (LEGACY_KEY_ALIAS.equals(alias)
				&& keyStore.containsAlias(VAULT_BIOMETRIC_KEY_ALIAS)) {
			keyStore.deleteEntry(VAULT_BIOMETRIC_KEY_ALIAS);
		}
	}

	public boolean hasVaultKey() throws KeyStoreException {
		return keyStore.containsAlias(alias());
	}

	public static void deleteAllVaultKeys() {
		try {
			KeyStore ks = KeyStore.getInstance(KEYSTORE_PROVIDER);
			ks.load(null);
			java.util.List<String> doomed = new java.util.ArrayList<>();
			java.util.Enumeration<String> aliases = ks.aliases();
			while (aliases.hasMoreElements()) {
				String a = aliases.nextElement();
				if (a.equals(LEGACY_KEY_ALIAS)
						|| a.startsWith(PROFILE_KEY_ALIAS_PREFIX)
						|| a.equals(VAULT_BIOMETRIC_KEY_ALIAS)) {
					doomed.add(a);
				}
			}
			for (String a : doomed) ks.deleteEntry(a);
		} catch (Exception ignored) {
		}
	}

}