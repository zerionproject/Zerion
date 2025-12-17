package org.briarproject.bramble.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

/**
 * Result of hybrid KEM encapsulation (ML-KEM-768).
 * <p>
 * Contains the ciphertext to send to the recipient and the shared secret
 * for the sender to use in deriving the final key.
 */
@NotNullByDefault
public class HybridEncapsulationResult {

	private final byte[] ciphertext;
	private final byte[] sharedSecret;

	public HybridEncapsulationResult(byte[] ciphertext, byte[] sharedSecret) {
		this.ciphertext = ciphertext;
		this.sharedSecret = sharedSecret;
	}

	/**
	 * Returns the KEM ciphertext to send to the recipient.
	 * The recipient will use this with their private key to derive the same
	 * shared secret.
	 *
	 * @return The ciphertext (1,088 bytes for ML-KEM-768)
	 */
	public byte[] getCiphertext() {
		return ciphertext;
	}

	/**
	 * Returns the KEM shared secret for use by the encapsulator.
	 * This should be combined with the ECDH shared secret to derive the
	 * final hybrid shared secret.
	 *
	 * @return The shared secret (32 bytes)
	 */
	public byte[] getSharedSecret() {
		return sharedSecret;
	}

	/**
	 * Securely clears the shared secret from memory.
	 * Call this after the secret has been used.
	 */
	public void clearSecret() {
		Arrays.fill(sharedSecret, (byte) 0);
	}
}
