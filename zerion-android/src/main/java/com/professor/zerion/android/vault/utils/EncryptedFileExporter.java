package com.professor.zerion.android.vault.utils;

import com.professor.zerion.android.vault.crypto.Argon2;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@NotNullByDefault
public class EncryptedFileExporter {

	private static final byte[] MAGIC_HEADER_V2 = "ZENC2\0".getBytes(StandardCharsets.US_ASCII);
	private static final int SALT_LENGTH = 32;
	private static final int NONCE_LENGTH = 12;
	private static final int KEY_LENGTH_BYTES = 32;
	private static final int GCM_TAG_LENGTH = 128;

	public static byte[] exportEncrypted(String filename, byte[] content, char[] password)
			throws Exception {
		if (filename == null || filename.isEmpty()) {
			throw new IllegalArgumentException("Filename cannot be empty");
		}
		if (content == null || content.length == 0) {
			throw new IllegalArgumentException("Content cannot be empty");
		}
		if (password == null || password.length == 0) {
			throw new IllegalArgumentException("Password cannot be empty");
		}

		SecureRandom random = new SecureRandom();
		byte[] salt = null;
		byte[] key = null;
		byte[] filenameBytes = null;
		byte[] filenameNonce = null;
		byte[] contentNonce = null;

		try {
			salt = new byte[SALT_LENGTH];
			random.nextBytes(salt);

			Argon2.Argon2Params params = Argon2.Argon2Params.getDefault();
			Argon2 argon2 = new Argon2();
			key = argon2.deriveKey(password, salt, params);

			filenameBytes = filename.getBytes(StandardCharsets.UTF_8);
			filenameNonce = new byte[NONCE_LENGTH];
			random.nextBytes(filenameNonce);
			byte[] encryptedFilename = encryptAesGcm(filenameBytes, key, filenameNonce);

			contentNonce = new byte[NONCE_LENGTH];
			random.nextBytes(contentNonce);
			byte[] encryptedContent = encryptAesGcm(content, key, contentNonce);

			ByteArrayOutputStream output = new ByteArrayOutputStream();

			output.write(MAGIC_HEADER_V2);

			ByteBuffer paramBuf = ByteBuffer.allocate(12);
			paramBuf.putInt(params.memoryKb);
			paramBuf.putInt(params.iterations);
			paramBuf.putInt(params.parallelism);
			output.write(paramBuf.array());

			ByteBuffer lengthBuffer = ByteBuffer.allocate(4);
			lengthBuffer.putInt(encryptedFilename.length);
			output.write(lengthBuffer.array());
			output.write(encryptedFilename);

			output.write(salt);

			output.write(filenameNonce);

			output.write(contentNonce);

			output.write(encryptedContent);

			return output.toByteArray();

		} finally {
			if (salt != null) SecureMemory.shred(salt);
			if (key != null) SecureMemory.shred(key);
			if (filenameBytes != null) SecureMemory.shred(filenameBytes);
			if (filenameNonce != null) SecureMemory.shred(filenameNonce);
			if (contentNonce != null) SecureMemory.shred(contentNonce);
			if (password != null) java.util.Arrays.fill(password, '\0');
		}
	}

	public static DecryptedDocument importEncrypted(byte[] zencData, char[] password)
			throws Exception {
		if (zencData == null || zencData.length < 100) {
			throw new IllegalArgumentException("Invalid .zenc file");
		}
		if (password == null || password.length == 0) {
			throw new IllegalArgumentException("Password cannot be empty");
		}

		ByteBuffer buffer = ByteBuffer.wrap(zencData);
		byte[] key = null;
		byte[] salt = null;
		byte[] filenameNonce = null;
		byte[] contentNonce = null;
		byte[] filenameBytes = null;

		try {
			byte[] magic = new byte[MAGIC_HEADER_V2.length];
			buffer.get(magic);
			if (!java.util.Arrays.equals(magic, MAGIC_HEADER_V2)) {
				throw new IllegalArgumentException("Invalid .zenc file format");
			}

			int memoryKb = buffer.getInt();
			int iterations = buffer.getInt();
			int parallelism = buffer.getInt();
			if (memoryKb < 1024 || iterations < 1 || parallelism < 1) {
				throw new IllegalArgumentException(
						"Invalid Argon2 parameters in .zenc header");
			}

			int filenameLength = buffer.getInt();
			if (filenameLength <= 0 || filenameLength > 1024) {
				throw new IllegalArgumentException("Invalid filename length");
			}
			byte[] encryptedFilename = new byte[filenameLength];
			buffer.get(encryptedFilename);

			salt = new byte[SALT_LENGTH];
			buffer.get(salt);

			filenameNonce = new byte[NONCE_LENGTH];
			buffer.get(filenameNonce);
			contentNonce = new byte[NONCE_LENGTH];
			buffer.get(contentNonce);

			byte[] encryptedContent = new byte[buffer.remaining()];
			buffer.get(encryptedContent);

			Argon2 argon2 = new Argon2();
			Argon2.Argon2Params params = new Argon2.Argon2Params(
					memoryKb, iterations, parallelism, KEY_LENGTH_BYTES);
			key = argon2.deriveKey(password, salt, params);

			filenameBytes = decryptAesGcm(encryptedFilename, key, filenameNonce);
			String filename = new String(filenameBytes, StandardCharsets.UTF_8);

			byte[] content = decryptAesGcm(encryptedContent, key, contentNonce);

			return new DecryptedDocument(filename, content);

		} finally {
			if (key != null) SecureMemory.shred(key);
			if (salt != null) SecureMemory.shred(salt);
			if (filenameNonce != null) SecureMemory.shred(filenameNonce);
			if (contentNonce != null) SecureMemory.shred(contentNonce);
			if (filenameBytes != null) SecureMemory.shred(filenameBytes);
			if (password != null) java.util.Arrays.fill(password, '\0');
		}
	}

	private static byte[] encryptAesGcm(byte[] plaintext, byte[] key, byte[] nonce)
			throws Exception {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
		GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, nonce);
		cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
		return cipher.doFinal(plaintext);
	}

	private static byte[] decryptAesGcm(byte[] ciphertext, byte[] key, byte[] nonce)
			throws Exception {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
		GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, nonce);
		cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
		return cipher.doFinal(ciphertext);
	}

	public static class DecryptedDocument {
		public final String filename;
		public final byte[] content;

		public DecryptedDocument(String filename, byte[] content) {
			this.filename = filename;
			this.content = content;
		}

		public void shred() {
			if (content != null) {
				SecureMemory.shred(content);
			}
		}
	}
}
