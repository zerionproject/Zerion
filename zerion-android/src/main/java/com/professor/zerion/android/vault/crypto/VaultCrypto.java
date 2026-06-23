package com.professor.zerion.android.vault.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@NotNullByDefault
public class VaultCrypto {

	private static final String AES_GCM = "AES/GCM/NoPadding";
	private static final int GCM_NONCE_LENGTH = 12;
	private static final int GCM_TAG_LENGTH = 16;
	private static final int KEY_LENGTH = 32;

	private final SecureRandom secureRandom = new SecureRandom();

	public EncryptedData encrypt(byte[] plaintext, byte[] key, byte[] associatedData) {
		if (key.length != KEY_LENGTH) {
			throw new IllegalArgumentException("Key must be 256 bits");
		}

		try {
			return encryptAesGcm(plaintext, key, associatedData);
		} catch (Exception e) {
			throw new RuntimeException("Encryption failed", e);
		}
	}

	public byte[] decrypt(EncryptedData encryptedData, byte[] key, byte[] associatedData) {
		if (key.length != KEY_LENGTH) {
			throw new IllegalArgumentException("Key must be 256 bits");
		}

		try {
			return decryptAesGcm(encryptedData, key, associatedData);
		} catch (Exception e) {
			throw new RuntimeException("Decryption failed", e);
		}
	}

	private EncryptedData encryptAesGcm(byte[] plaintext, byte[] key, byte[] associatedData)
			throws NoSuchPaddingException, NoSuchAlgorithmException,
			InvalidAlgorithmParameterException, InvalidKeyException,
			BadPaddingException, IllegalBlockSizeException {

		byte[] nonce = new byte[GCM_NONCE_LENGTH];
		secureRandom.nextBytes(nonce);

		Cipher cipher = Cipher.getInstance(AES_GCM);
		SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
		GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, nonce);

		cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
		if (associatedData != null) {
			cipher.updateAAD(associatedData);
		}
		byte[] ciphertext = cipher.doFinal(plaintext);

		return new EncryptedData(nonce, ciphertext);
	}

	private byte[] decryptAesGcm(EncryptedData encryptedData, byte[] key, byte[] associatedData)
			throws NoSuchPaddingException, NoSuchAlgorithmException,
			InvalidAlgorithmParameterException, InvalidKeyException,
			BadPaddingException, IllegalBlockSizeException {

		Cipher cipher = Cipher.getInstance(AES_GCM);
		SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
		GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH * 8, encryptedData.nonce);

		cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);
		if (associatedData != null) {
			cipher.updateAAD(associatedData);
		}

		return cipher.doFinal(encryptedData.ciphertext);
	}

	public byte[] hkdfSha256(byte[] inputKeyMaterial, byte[] salt, String info,
			int outputLength) {
		try {
			byte[] effectiveSalt = salt == null || salt.length == 0
					? new byte[32]
					: salt;
			Mac extractMac = Mac.getInstance("HmacSHA256");
			extractMac.init(new SecretKeySpec(effectiveSalt, "HmacSHA256"));
			byte[] prk = extractMac.doFinal(inputKeyMaterial);

			byte[] infoBytes = info.getBytes(java.nio.charset.StandardCharsets.UTF_8);
			int hashLen = 32;
			int n = (outputLength + hashLen - 1) / hashLen;
			byte[] okm = new byte[n * hashLen];
			byte[] t = new byte[0];

			Mac expandMac = Mac.getInstance("HmacSHA256");
			expandMac.init(new SecretKeySpec(prk, "HmacSHA256"));

			for (int i = 1; i <= n; i++) {
				expandMac.reset();
				expandMac.update(t);
				expandMac.update(infoBytes);
				expandMac.update((byte) i);
				t = expandMac.doFinal();
				System.arraycopy(t, 0, okm, (i - 1) * hashLen, hashLen);
			}

			byte[] output = new byte[outputLength];
			System.arraycopy(okm, 0, output, 0, outputLength);
			Arrays.fill(prk, (byte) 0);
			Arrays.fill(okm, (byte) 0);
			return output;
		} catch (Exception e) {
			throw new RuntimeException("HKDF failed", e);
		}
	}

	public byte[] xor(byte[] a, byte[] b) {
		if (a.length != b.length) {
			throw new IllegalArgumentException("Arrays must have same length");
		}

		byte[] result = new byte[a.length];
		for (int i = 0; i < a.length; i++) {
			result[i] = (byte) (a[i] ^ b[i]);
		}
		return result;
	}

	public byte[] generateKey() {
		byte[] key = new byte[KEY_LENGTH];
		secureRandom.nextBytes(key);
		return key;
	}

	public byte[] generateNonce() {
		byte[] nonce = new byte[GCM_NONCE_LENGTH];
		secureRandom.nextBytes(nonce);
		return nonce;
	}

	public byte[] generateXChaCha20Nonce() {
		byte[] nonce = new byte[24];
		secureRandom.nextBytes(nonce);
		return nonce;
	}

	public byte[] computePasswordVerificationMac(byte[] masterKey) {
		try {
			Mac hmac = Mac.getInstance("HmacSHA256");
			SecretKeySpec keySpec = new SecretKeySpec(masterKey, "HmacSHA256");
			hmac.init(keySpec);
			return hmac.doFinal("VAULT_PASSWORD_VERIFICATION".getBytes());
		} catch (Exception e) {
			throw new RuntimeException("Failed to compute password MAC", e);
		}
	}

	public boolean verifyPasswordMac(byte[] masterKey, byte[] storedMac) {
		try {
			byte[] computedMac = computePasswordVerificationMac(masterKey);
			return MessageDigest.isEqual(computedMac, storedMac);
		} catch (Exception e) {
			return false;
		}
	}

	public static class EncryptedData {
		public final byte[] nonce;
		public final byte[] ciphertext;

		public EncryptedData(byte[] nonce, byte[] ciphertext) {
			this.nonce = nonce;
			this.ciphertext = ciphertext;
		}

		public byte[] toBytes() {
			ByteBuffer buffer = ByteBuffer.allocate(4 + nonce.length + ciphertext.length);
			buffer.putInt(nonce.length);
			buffer.put(nonce);
			buffer.put(ciphertext);
			return buffer.array();
		}

		public static EncryptedData fromBytes(byte[] data) {
			if (data == null || data.length < 4) {
				throw new IllegalArgumentException("Invalid encrypted data");
			}
			ByteBuffer buffer = ByteBuffer.wrap(data);
			int nonceLength = buffer.getInt();
			if (nonceLength != GCM_NONCE_LENGTH
					|| buffer.remaining() < nonceLength) {
				throw new IllegalArgumentException("Invalid nonce length");
			}
			byte[] nonce = new byte[nonceLength];
			buffer.get(nonce);
			byte[] ciphertext = new byte[buffer.remaining()];
			buffer.get(ciphertext);
			return new EncryptedData(nonce, ciphertext);
		}
	}

	public static class ChunkedEncryption {
		private static final int CHUNK_SIZE = 4 * 1024 * 1024;

		private final VaultCrypto crypto = new VaultCrypto();
		private final byte[] masterKey;
		private final byte[] associatedData;

		public ChunkedEncryption(byte[] masterKey, byte[] associatedData) {
			this.masterKey = masterKey;
			this.associatedData = associatedData;
		}

		public ChunkedData encryptChunked(byte[] data) {
			int numChunks = (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
			EncryptedData[] chunks = new EncryptedData[numChunks];

			for (int i = 0; i < numChunks; i++) {
				int start = i * CHUNK_SIZE;
				int end = Math.min(start + CHUNK_SIZE, data.length);
				byte[] chunk = Arrays.copyOfRange(data, start, end);

				String info = "chunk_" + i;
				byte[] chunkKey = crypto.hkdfSha256(masterKey, null, info,
						KEY_LENGTH);

				chunks[i] = crypto.encrypt(chunk, chunkKey, associatedData);

				Arrays.fill(chunkKey, (byte) 0);
			}

			return new ChunkedData(chunks, data.length);
		}

		public byte[] decryptChunked(ChunkedData chunkedData) {
			ByteBuffer result = ByteBuffer.allocate(chunkedData.originalSize);

			for (int i = 0; i < chunkedData.chunks.length; i++) {
				String info = "chunk_" + i;
				byte[] chunkKey = crypto.hkdfSha256(masterKey, null, info,
						KEY_LENGTH);

				byte[] chunk = crypto.decrypt(chunkedData.chunks[i], chunkKey, associatedData);
				result.put(chunk);

				Arrays.fill(chunkKey, (byte) 0);
				Arrays.fill(chunk, (byte) 0);
			}

			return result.array();
		}
	}

	public static class ChunkedData {
		public final EncryptedData[] chunks;
		public final int originalSize;

		public ChunkedData(EncryptedData[] chunks, int originalSize) {
			this.chunks = chunks;
			this.originalSize = originalSize;
		}
	}
}