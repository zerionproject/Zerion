package org.briarproject.briar.conversation.voice;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.rendezvous.KeyMaterialSource;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

/**
 * A KeyMaterialSource implementation for voice calls.
 * <p>
 * This generates deterministic key material using HMAC-SHA256,
 * which is available in standard Java crypto. Both parties can derive
 * the same key material from the shared voice call key.
 * <p>
 * Uses HKDF-like expansion: output = HMAC(key, counter)
 * where counter increments for each block of key material.
 * <p>
 * Thread-safe: Multiple threads can call getKeyMaterial() concurrently.
 */
@ThreadSafe
@NotNullByDefault
class VoiceCallKeyMaterialSource implements KeyMaterialSource {

	private static final String HMAC_ALGORITHM = "HmacSHA256";

	private final SecretKey sourceKey;

	@GuardedBy("this")
	private int counter = 0;

	/**
	 * Creates a KeyMaterialSource from a voice call key.
	 *
	 * @param sourceKey The key derived for this voice call and transport
	 */
	VoiceCallKeyMaterialSource(SecretKey sourceKey) {
		this.sourceKey = sourceKey;
	}

	@Override
	public synchronized byte[] getKeyMaterial(int length) {
		try {
			byte[] result = new byte[length];
			int offset = 0;

			// Generate key material in HMAC_LENGTH blocks
			while (offset < length) {
				// Generate next block: HMAC(sourceKey, counter)
				Mac mac = Mac.getInstance(HMAC_ALGORITHM);
				mac.init(new SecretKeySpec(sourceKey.getBytes(), HMAC_ALGORITHM));

				// Include counter as input to make each block unique
				byte[] counterBytes = intToBytes(counter++);
				byte[] block = mac.doFinal(counterBytes);

				// Copy block to result
				int toCopy = Math.min(block.length, length - offset);
				System.arraycopy(block, 0, result, offset, toCopy);
				offset += toCopy;
			}

			return result;
		} catch (GeneralSecurityException e) {
			throw new RuntimeException("Failed to generate key material", e);
		}
	}

	/**
	 * Converts an integer to 4 bytes (big-endian).
	 */
	private byte[] intToBytes(int value) {
		return new byte[] {
			(byte) (value >>> 24),
			(byte) (value >>> 16),
			(byte) (value >>> 8),
			(byte) value
		};
	}
}
