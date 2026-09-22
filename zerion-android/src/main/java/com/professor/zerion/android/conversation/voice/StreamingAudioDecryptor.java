package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@NotNullByDefault
public class StreamingAudioDecryptor {

	private static final ThreadLocal<Cipher> CIPHER_CACHE =
			ThreadLocal.withInitial(() -> {
				try {
					return Cipher.getInstance("AES/GCM/NoPadding");
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
			});

	private static final int AES_KEY_SIZE = 256;
	private static final int GCM_IV_LENGTH = 12;
	private static final int GCM_TAG_LENGTH = 128;

	private final byte[] rawKeyBytes;
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
		this.rawKeyBytes = Arrays.copyOf(sessionKey, sessionKey.length);
		this.baseIv = Arrays.copyOf(iv, iv.length);
		this.aadContext = new byte[0];
	}

	private static byte[] unwrapSessionKey(byte[] wrapKeyBytes, byte[] iv,
			byte[] sealedSessionKey) throws Exception {
		if (wrapKeyBytes.length != VoiceMemoCrypto.WRAP_KEY_LENGTH) {
			throw new SecurityException("Wrap key must be "
					+ VoiceMemoCrypto.WRAP_KEY_LENGTH + " bytes");
		}
		SecretKeySpec wrapKey = new SecretKeySpec(wrapKeyBytes, "AES");
		Cipher cipher = CIPHER_CACHE.get();
		cipher.init(Cipher.DECRYPT_MODE, wrapKey,
				new GCMParameterSpec(GCM_TAG_LENGTH, iv));
		return cipher.doFinal(sealedSessionKey);
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

	public byte[] decryptChunk(byte[] ciphertext, byte[] tag) throws Exception {
		if (tag.length != GCM_TAG_LENGTH / 8) {
			throw new IllegalArgumentException("Tag must be 16 bytes, got " + tag.length);
		}

		byte[] ivWithCounter = new byte[GCM_IV_LENGTH];
		System.arraycopy(baseIv, 0, ivWithCounter, 0, GCM_IV_LENGTH);

		int counter = chunkSequenceNumber++;
		ivWithCounter[GCM_IV_LENGTH - 4] = (byte) (baseIv[GCM_IV_LENGTH - 4] ^ (byte) (counter >>> 24));
		ivWithCounter[GCM_IV_LENGTH - 3] = (byte) (baseIv[GCM_IV_LENGTH - 3] ^ (byte) (counter >>> 16));
		ivWithCounter[GCM_IV_LENGTH - 2] = (byte) (baseIv[GCM_IV_LENGTH - 2] ^ (byte) (counter >>> 8));
		ivWithCounter[GCM_IV_LENGTH - 1] = (byte) (baseIv[GCM_IV_LENGTH - 1] ^ (byte) counter);

		byte[] combined = new byte[ciphertext.length + tag.length];
		System.arraycopy(ciphertext, 0, combined, 0, ciphertext.length);
		System.arraycopy(tag, 0, combined, ciphertext.length, tag.length);

		Cipher cipher = CIPHER_CACHE.get();
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(rawKeyBytes, "AES"),
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
		ivWithCounter[GCM_IV_LENGTH - 4] = (byte) (baseIv[GCM_IV_LENGTH - 4] ^ (byte) (counter >>> 24));
		ivWithCounter[GCM_IV_LENGTH - 3] = (byte) (baseIv[GCM_IV_LENGTH - 3] ^ (byte) (counter >>> 16));
		ivWithCounter[GCM_IV_LENGTH - 2] = (byte) (baseIv[GCM_IV_LENGTH - 2] ^ (byte) (counter >>> 8));
		ivWithCounter[GCM_IV_LENGTH - 1] = (byte) (baseIv[GCM_IV_LENGTH - 1] ^ (byte) counter);

		Cipher cipher = CIPHER_CACHE.get();
		cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(rawKeyBytes, "AES"),
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

	/**
	 * Opens a memo for playback. A format 2 memo derives its wrap key from the
	 * pairing secret and the memo's salt and verifies only under the identity
	 * of the message it was recorded for. A format 1 memo, which carried its
	 * wrap key in the payload and bound no message identity, is opened only
	 * because it may already be stored from before the format changed; the
	 * validator refuses that format on receipt, so no such memo arrives now.
	 */
	public static byte[] decryptAll(
			VoiceMessagePayloadParser.ParsedPayload payload, byte[] groupId,
			long timestamp, byte[] senderId, byte[] recipientId,
			VoiceMemoKeys keys) throws Exception {
		if (groupId.length != 32) {
			throw new IllegalArgumentException("groupId must be 32 bytes, got " + groupId.length);
		}
		byte[] wrapKey;
		byte[] binding;
		if (payload.formatVersion == VoiceMemoCrypto.FORMAT_VERSION) {
			wrapKey = keys.deriveWrapKey(
					VoiceMemoCrypto.fieldPrefix(payload.wrappedKey));
			binding = VoiceMemoCrypto.messageBinding(timestamp, senderId,
					recipientId);
		} else if (payload.formatVersion
				== VoiceMemoCrypto.LEGACY_FORMAT_VERSION) {
			wrapKey = VoiceMemoCrypto.fieldPrefix(payload.wrappedKey);
			binding = new byte[0];
		} else {
			throw new SecurityException("Unsupported voice memo format");
		}
		byte[] sessionKey;
		try {
			sessionKey = unwrapSessionKey(wrapKey, payload.iv,
					VoiceMemoCrypto.sealedSessionKey(payload.wrappedKey));
		} finally {
			Arrays.fill(wrapKey, (byte) 0);
		}

		StreamingAudioDecryptor decryptor =
				new StreamingAudioDecryptor(sessionKey, payload.iv);
		decryptor.setAADContext(new byte[] {payload.formatVersion}, groupId,
				binding);

		ByteArrayOutputStream plaintext = new ByteArrayOutputStream();

		try {
			for (int i = 0; i < payload.chunks.size(); i++) {
				byte[] decryptedChunk = decryptor.decryptChunk(
						payload.chunks.get(i), payload.tags.get(i));
				plaintext.write(decryptedChunk);
				Arrays.fill(decryptedChunk, (byte) 0);
			}

			decryptor.verifyGlobalMAC(payload.chunks.size(),
					payload.durationMs, payload.globalMAC);

			return plaintext.toByteArray();
		} finally {
			Arrays.fill(sessionKey, (byte) 0);
			decryptor.zeroizeKeys();
		}
	}

	public void zeroizeKeys() {
		if (rawKeyBytes != null) {
			Arrays.fill(rawKeyBytes, (byte) 0);
		}
		Arrays.fill(baseIv, (byte) 0);
		if (aadContext != null) {
			Arrays.fill(aadContext, (byte) 0);
		}
	}
}
