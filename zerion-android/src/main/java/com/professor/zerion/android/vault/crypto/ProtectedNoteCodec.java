package com.professor.zerion.android.vault.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.annotation.Nullable;
import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

@NotNullByDefault
public final class ProtectedNoteCodec {

	public static final String TITLE_PREFIX = "🔒 ";

	private static final byte VERSION_2 = 0x02;
	private static final int V2_SALT_BYTES = 32;
	private static final int V2_HEADER_BYTES = 45;
	private static final int V1_SALT_BYTES = 16;
	private static final int V1_HEADER_BYTES = 28;
	private static final int V1_ITERATIONS = 200000;
	private static final int IV_BYTES = 12;
	private static final int KEY_BYTES = 32;

	private ProtectedNoteCodec() {
	}

	public static byte[] seal(String content, char[] password)
			throws Exception {
		byte[] salt = new byte[V2_SALT_BYTES];
		new SecureRandom().nextBytes(salt);
		byte[] passwordKey = null;
		byte[] contentBytes = null;
		byte[] encryptedContent = null;
		try {
			Argon2 argon2 = new Argon2();
			Argon2.Argon2Params params = Argon2.Argon2Params.getDefault();
			passwordKey = argon2.deriveKey(password, salt, params);

			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			SecretKeySpec keySpec = new SecretKeySpec(passwordKey, "AES");
			cipher.init(Cipher.ENCRYPT_MODE, keySpec);

			contentBytes = content.getBytes(StandardCharsets.UTF_8);
			byte[] iv = cipher.getIV();
			encryptedContent = cipher.doFinal(contentBytes);

			byte[] combined = new byte[1 + salt.length + iv.length
					+ encryptedContent.length];
			combined[0] = VERSION_2;
			System.arraycopy(salt, 0, combined, 1, salt.length);
			System.arraycopy(iv, 0, combined, 1 + salt.length, iv.length);
			System.arraycopy(encryptedContent, 0, combined,
					1 + salt.length + iv.length, encryptedContent.length);
			return combined;
		} finally {
			if (contentBytes != null) Arrays.fill(contentBytes, (byte) 0);
			if (passwordKey != null) Arrays.fill(passwordKey, (byte) 0);
			if (encryptedContent != null) {
				Arrays.fill(encryptedContent, (byte) 0);
			}
			Arrays.fill(salt, (byte) 0);
		}
	}

	@Nullable
	public static byte[] open(byte[] encryptedData, char[] password)
			throws Exception {
		byte[] decryptedContent = null;
		if (encryptedData.length > V2_HEADER_BYTES
				&& encryptedData[0] == VERSION_2) {
			try {
				decryptedContent = openV2(encryptedData, password);
			} catch (BadPaddingException e) {
				decryptedContent = null;
			}
		}
		if (decryptedContent == null
				&& encryptedData.length > V1_HEADER_BYTES) {
			try {
				decryptedContent = openV1(encryptedData, password);
			} catch (BadPaddingException e) {
				decryptedContent = null;
			}
		}
		return decryptedContent;
	}

	private static byte[] openV2(byte[] encryptedData, char[] password)
			throws Exception {
		byte[] salt = new byte[V2_SALT_BYTES];
		byte[] iv = new byte[IV_BYTES];
		byte[] encryptedContent =
				new byte[encryptedData.length - V2_HEADER_BYTES];
		System.arraycopy(encryptedData, 1, salt, 0, V2_SALT_BYTES);
		System.arraycopy(encryptedData, 1 + V2_SALT_BYTES, iv, 0, IV_BYTES);
		System.arraycopy(encryptedData, V2_HEADER_BYTES, encryptedContent, 0,
				encryptedContent.length);
		Argon2 argon2 = new Argon2();
		byte[] key = argon2.deriveKey(password, salt,
				Argon2.Argon2Params.getDefault());
		try {
			return aesGcmDecrypt(key, iv, encryptedContent);
		} finally {
			Arrays.fill(key, (byte) 0);
			Arrays.fill(salt, (byte) 0);
		}
	}

	private static byte[] openV1(byte[] encryptedData, char[] password)
			throws Exception {
		byte[] salt = new byte[V1_SALT_BYTES];
		byte[] iv = new byte[IV_BYTES];
		byte[] encryptedContent =
				new byte[encryptedData.length - V1_HEADER_BYTES];
		System.arraycopy(encryptedData, 0, salt, 0, V1_SALT_BYTES);
		System.arraycopy(encryptedData, V1_SALT_BYTES, iv, 0, IV_BYTES);
		System.arraycopy(encryptedData, V1_HEADER_BYTES, encryptedContent, 0,
				encryptedContent.length);
		byte[] key = pbkdf2(password, salt, V1_ITERATIONS, KEY_BYTES);
		try {
			return aesGcmDecrypt(key, iv, encryptedContent);
		} finally {
			Arrays.fill(key, (byte) 0);
			Arrays.fill(salt, (byte) 0);
		}
	}

	private static byte[] aesGcmDecrypt(byte[] key, byte[] iv, byte[] data)
			throws Exception {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
				new GCMParameterSpec(128, iv));
		return cipher.doFinal(data);
	}

	private static byte[] pbkdf2(char[] pw, byte[] salt, int iter, int len)
			throws Exception {
		PBEKeySpec spec = new PBEKeySpec(pw, salt, iter, len * 8);
		try {
			return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
					.generateSecret(spec).getEncoded();
		} finally {
			spec.clearPassword();
		}
	}
}
