package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@NotNullByDefault
public class StreamingAudioEncryptor {

	private static final int AES_KEY_SIZE = 256;
	private static final int GCM_IV_LENGTH = 12;
	private static final int GCM_TAG_LENGTH = 128;
	private static final int CHUNK_SIZE = 4096;

	private final SecretKey encryptionKey;
	private final byte[] rawKeyBytes; // retain raw bytes for zeroing
	private final byte[] iv;
	private int chunkSequenceNumber = 0;
	private byte[] aadContext;

	public StreamingAudioEncryptor() throws Exception {
		KeyGenerator keyGen = KeyGenerator.getInstance("AES");
		keyGen.init(AES_KEY_SIZE, new SecureRandom());
		this.encryptionKey = keyGen.generateKey();
		this.rawKeyBytes = encryptionKey.getEncoded();
		this.iv = new byte[GCM_IV_LENGTH];
		new SecureRandom().nextBytes(iv);
		this.aadContext = new byte[0];
	}

	public void setAADContext(byte[] formatVersion, byte[] conversationId, byte[] messageId) {
		if (formatVersion.length != 1) {
			throw new IllegalArgumentException("formatVersion must be 1 byte, got " + formatVersion.length);
		}
		if (conversationId.length != 32) {
			throw new IllegalArgumentException("conversationId must be 32 bytes, got " + conversationId.length);
		}
		ByteBuffer buffer = ByteBuffer.allocate(formatVersion.length + conversationId.length + messageId.length);
		buffer.put(formatVersion);
		buffer.put(conversationId);
		buffer.put(messageId);
		this.aadContext = buffer.array();
	}

	public byte[] getIV() {
		return Arrays.copyOf(iv, iv.length);
	}

	public byte[] getSessionKey() {
		byte[] keyBytes = encryptionKey.getEncoded();
		return Arrays.copyOf(keyBytes, keyBytes.length);
	}

	public byte[] getEncryptedKey(SecretKey masterKey) throws Exception {
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, masterKey,
			new GCMParameterSpec(GCM_TAG_LENGTH, iv));
		byte[] keyBytes = encryptionKey.getEncoded();
		byte[] wrapped = cipher.doFinal(keyBytes);
		Arrays.fill(keyBytes, (byte) 0);
		return wrapped;
	}

	public EncryptedChunk encryptChunk(byte[] plaintext, int len) throws Exception {
		if (chunkSequenceNumber == 0xFFFFFFFF) {
			throw new IllegalStateException("Chunk sequence overflow");
		}

		byte[] ivWithCounter = new byte[GCM_IV_LENGTH];
		System.arraycopy(iv, 0, ivWithCounter, 0, GCM_IV_LENGTH);

		int counter = chunkSequenceNumber++;
		ivWithCounter[GCM_IV_LENGTH - 4] ^= (byte) (counter >>> 24);
		ivWithCounter[GCM_IV_LENGTH - 3] ^= (byte) (counter >>> 16);
		ivWithCounter[GCM_IV_LENGTH - 2] ^= (byte) (counter >>> 8);
		ivWithCounter[GCM_IV_LENGTH - 1] ^= (byte) counter;

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, encryptionKey,
			new GCMParameterSpec(GCM_TAG_LENGTH, ivWithCounter));

		if (aadContext.length > 0) {
			cipher.updateAAD(aadContext);
		}

		byte[] encrypted = cipher.doFinal(plaintext, 0, len);

		byte[] ciphertextOnly = Arrays.copyOf(encrypted, encrypted.length - (GCM_TAG_LENGTH / 8));
		byte[] tagPart = new byte[GCM_TAG_LENGTH / 8];
		System.arraycopy(encrypted, ciphertextOnly.length, tagPart, 0, tagPart.length);

		Arrays.fill(ivWithCounter, (byte) 0);
		Arrays.fill(encrypted, (byte) 0);

		return new EncryptedChunk(ciphertextOnly, tagPart);
	}

	public byte[] computeGlobalMAC(int chunkCount, int durationMs) throws Exception {
		byte[] ivWithCounter = new byte[GCM_IV_LENGTH];
		System.arraycopy(iv, 0, ivWithCounter, 0, GCM_IV_LENGTH);

		int counter = chunkSequenceNumber;
		ivWithCounter[GCM_IV_LENGTH - 4] ^= (byte) (counter >>> 24);
		ivWithCounter[GCM_IV_LENGTH - 3] ^= (byte) (counter >>> 16);
		ivWithCounter[GCM_IV_LENGTH - 2] ^= (byte) (counter >>> 8);
		ivWithCounter[GCM_IV_LENGTH - 1] ^= (byte) counter;

		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(Cipher.ENCRYPT_MODE, encryptionKey,
			new GCMParameterSpec(GCM_TAG_LENGTH, ivWithCounter));

		ByteBuffer metadata = ByteBuffer.allocate(8);
		metadata.putInt(chunkCount);
		metadata.putInt(durationMs);

		if (aadContext.length > 0) {
			cipher.updateAAD(aadContext);
		}
		cipher.updateAAD(metadata.array());

		byte[] encrypted = cipher.doFinal(new byte[0]);
		byte[] globalMAC = new byte[GCM_TAG_LENGTH / 8];
		System.arraycopy(encrypted, 0, globalMAC, 0, globalMAC.length);

		Arrays.fill(ivWithCounter, (byte) 0);
		Arrays.fill(encrypted, (byte) 0);
		Arrays.fill(metadata.array(), (byte) 0);

		return globalMAC;
	}

	public void zeroizeKeys() {
		// Zero the actual raw key bytes
		if (rawKeyBytes != null) {
			Arrays.fill(rawKeyBytes, (byte) 0);
		}
		if (iv != null) {
			Arrays.fill(iv, (byte) 0);
		}
		if (aadContext != null) {
			Arrays.fill(aadContext, (byte) 0);
		}
	}

	public static class EncryptedChunk {
		public final byte[] ciphertext;
		public final byte[] tag;

		public EncryptedChunk(byte[] ciphertext, byte[] tag) {
			this.ciphertext = ciphertext;
			this.tag = tag;
		}
	}
}
