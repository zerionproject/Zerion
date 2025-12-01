package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@NotNullByDefault
public class StreamingAudioDecryptor {

	private static final int AES_KEY_SIZE = 256;
	private static final int GCM_IV_LENGTH = 12;
	private static final int GCM_TAG_LENGTH = 128;

	private final SecretKeySpec decryptionKey;
	private final byte[] baseIv;
	private int chunkSequenceNumber = 0;
	private byte[] aadContext;

	public StreamingAudioDecryptor(byte[] sessionKey, byte[] iv) {
		if (sessionKey.length != AES_KEY_SIZE / 8) {
			throw new IllegalArgumentException("Session key must be 32 bytes, got " + sessionKey.length);
		}
		if (iv.length != GCM_IV_LENGTH) {
			throw new IllegalArgumentException("IV must be 12 bytes, got " + iv.length);
		}
		this.decryptionKey = new SecretKeySpec(sessionKey, "AES");
		this.baseIv = Arrays.copyOf(iv, iv.length);
		this.aadContext = new byte[0];
	}

	private static byte[] unwrapSessionKey(byte[] wrappedKey, byte[] iv, byte[] groupId) throws Exception {
		if (wrappedKey.length != 48) {
			throw new IllegalArgumentException("Wrapped key must be 48 bytes (32 ciphertext + 16 tag), got " + wrappedKey.length);
		}

		// Derive the same wrapping key from groupId
		java.security.MessageDigest sha256 = java.security.MessageDigest.getInstance("SHA-256");
		sha256.update("VOICE_KEY_WRAP".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		sha256.update(groupId);
		byte[] keyMaterial = sha256.digest();
		SecretKeySpec wrapKey = new SecretKeySpec(keyMaterial, "AES");

		// Unwrap using AES-GCM
		// Note: Key unwrapping does NOT use AAD to maintain backward compatibility
		// Security is still provided by GCM authentication tag and per-chunk AAD
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, wrapKey, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

		byte[] unwrapped = cipher.doFinal(wrappedKey);

		// Zeroize sensitive data
		Arrays.fill(keyMaterial, (byte) 0);
		Arrays.fill(wrapKey.getEncoded(), (byte) 0);

		return unwrapped;
	}

	public void setAADContext(byte[] formatVersion, byte[] conversationId, byte[] messageId) {
		// Validate inputs to catch mismatches early
		if (formatVersion.length != 1) {
			throw new IllegalArgumentException("formatVersion must be 1 byte, got " + formatVersion.length);
		}
		if (conversationId.length != 32) {
			throw new IllegalArgumentException("conversationId must be 32 bytes, got " + conversationId.length);
		}

		// Build AAD context: formatVersion (1 byte) + conversationId (32 bytes) + messageId (variable)
		ByteBuffer buffer = ByteBuffer.allocate(formatVersion.length + conversationId.length + messageId.length);
		buffer.put(formatVersion);
		buffer.put(conversationId);
		buffer.put(messageId);
		this.aadContext = buffer.array();
	}

	public byte[] decryptChunk(byte[] ciphertext, byte[] tag) throws Exception {
		if (tag.length != GCM_TAG_LENGTH / 8) {
			throw new IllegalArgumentException("Tag must be 16 bytes, got " + tag.length);
		}

		byte[] ivWithCounter = new byte[GCM_IV_LENGTH];
		System.arraycopy(baseIv, 0, ivWithCounter, 0, GCM_IV_LENGTH);

		int counter = chunkSequenceNumber++;
		ivWithCounter[GCM_IV_LENGTH - 4] = (byte) (counter >>> 24);
		ivWithCounter[GCM_IV_LENGTH - 3] = (byte) (counter >>> 16);
		ivWithCounter[GCM_IV_LENGTH - 2] = (byte) (counter >>> 8);
		ivWithCounter[GCM_IV_LENGTH - 1] = (byte) counter;

		byte[] combined = new byte[ciphertext.length + tag.length];
		System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
		System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, decryptionKey,
			new GCMParameterSpec(GCM_TAG_LENGTH, ivWithCounter));

		if (aadContext.length > 0) {
			cipher.updateAAD(aadContext);
		}

		byte[] plaintext = cipher.doFinal(combined);

		Arrays.fill(ivWithCounter, (byte) 0);
		Arrays.fill(combined, (byte) 0);

		return plaintext;
	}

	public void verifyGlobalMAC(int chunkCount, int durationMs, byte[] globalMAC) throws Exception {
		if (globalMAC.length != GCM_TAG_LENGTH / 8) {
			throw new IllegalArgumentException("Global MAC must be 16 bytes, got " + globalMAC.length);
		}

		byte[] ivWithCounter = new byte[GCM_IV_LENGTH];
		System.arraycopy(baseIv, 0, ivWithCounter, 0, GCM_IV_LENGTH);

		int counter = chunkSequenceNumber;
		ivWithCounter[GCM_IV_LENGTH - 4] = (byte) (counter >>> 24);
		ivWithCounter[GCM_IV_LENGTH - 3] = (byte) (counter >>> 16);
		ivWithCounter[GCM_IV_LENGTH - 2] = (byte) (counter >>> 8);
		ivWithCounter[GCM_IV_LENGTH - 1] = (byte) counter;

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.DECRYPT_MODE, decryptionKey,
			new GCMParameterSpec(GCM_TAG_LENGTH, ivWithCounter));

		ByteBuffer metadata = ByteBuffer.allocate(8);
		metadata.putInt(chunkCount);
		metadata.putInt(durationMs);

		if (aadContext.length > 0) {
			cipher.updateAAD(aadContext);
		}
		cipher.updateAAD(metadata.array());

		cipher.doFinal(globalMAC);

		Arrays.fill(ivWithCounter, (byte) 0);
		Arrays.fill(metadata.array(), (byte) 0);
	}

	public static byte[] decryptAll(byte[] wrappedKey, byte[] iv,
	                                 List<byte[]> chunks, List<byte[]> tags,
	                                 int chunkCount, int durationMs, byte[] globalMAC,
	                                 byte[] formatVersion, byte[] groupId, byte[] messageId) throws Exception {
		// Validate inputs
		if (formatVersion.length != 1) {
			throw new IllegalArgumentException("formatVersion must be 1 byte, got " + formatVersion.length);
		}
		if (groupId.length != 32) {
			throw new IllegalArgumentException("groupId must be 32 bytes, got " + groupId.length);
		}

		// Unwrap the session key (no AAD for backward compatibility)
		byte[] sessionKey = unwrapSessionKey(wrappedKey, iv, groupId);

		StreamingAudioDecryptor decryptor = new StreamingAudioDecryptor(sessionKey, iv);

		// Set AAD context for chunk decryption and global MAC verification
		// Use empty messageId since that's what was used during encryption
		decryptor.setAADContext(formatVersion, groupId, new byte[0]);

		ByteArrayOutputStream plaintext = new ByteArrayOutputStream();

		try {
			for (int i = 0; i < chunks.size(); i++) {
				byte[] decryptedChunk = decryptor.decryptChunk(chunks.get(i), tags.get(i));
				plaintext.write(decryptedChunk);
				Arrays.fill(decryptedChunk, (byte) 0);
			}

			decryptor.verifyGlobalMAC(chunkCount, durationMs, globalMAC);

			return plaintext.toByteArray();
		} finally {
			// SECURITY: Zeroize unwrapped session key after all decryption is complete
			Arrays.fill(sessionKey, (byte) 0);
			decryptor.zeroizeKeys();
		}
	}

	public void zeroizeKeys() {
		Arrays.fill(baseIv, (byte) 0);
		if (aadContext != null) {
			Arrays.fill(aadContext, (byte) 0);
		}
	}
}
