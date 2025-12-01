package org.briarproject.bramble.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PRIVATE_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_AGREEMENT;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.ML_KEM_768_PRIVATE_KEY_BYTES;

/**
 * Hybrid agreement private key combining X25519 and ML-KEM-768.
 * <p>
 * This key provides post-quantum security through the hybrid combination
 * of classical ECDH (X25519) and post-quantum KEM (ML-KEM-768).
 * <p>
 * <b>Key Structure (2,432 bytes):</b>
 * <pre>
 * ┌────────────────────┬─────────────────────────┐
 * │ X25519 (32 bytes)  │ ML-KEM-768 (2,400 bytes) │
 * └────────────────────┴─────────────────────────┘
 * </pre>
 * <p>
 * <b>Security Note:</b> This class provides a {@link #clear()} method to
 * securely erase the private key from memory when no longer needed.
 */
@Immutable
@NotNullByDefault
public class HybridAgreementPrivateKey implements PrivateKey {

	private final byte[] encoded;

	/**
	 * Creates a hybrid private key from the combined encoded form.
	 *
	 * @param encoded The combined key bytes (2,432 bytes)
	 * @throws IllegalArgumentException If the key length is incorrect
	 */
	public HybridAgreementPrivateKey(byte[] encoded) {
		if (encoded.length != HYBRID_AGREEMENT_PRIVATE_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid hybrid agreement private key length: " + encoded.length +
							", expected: " + HYBRID_AGREEMENT_PRIVATE_KEY_BYTES);
		}
		this.encoded = encoded;
	}

	/**
	 * Creates a hybrid private key from separate X25519 and ML-KEM-768 keys.
	 *
	 * @param x25519PrivateKey The X25519 private key (32 bytes)
	 * @param mlKemPrivateKey The ML-KEM-768 private key (2,400 bytes)
	 * @throws IllegalArgumentException If key lengths are incorrect
	 */
	public HybridAgreementPrivateKey(byte[] x25519PrivateKey, byte[] mlKemPrivateKey) {
		if (x25519PrivateKey.length != 32) {
			throw new IllegalArgumentException(
					"Invalid X25519 private key length: " + x25519PrivateKey.length);
		}
		if (mlKemPrivateKey.length != ML_KEM_768_PRIVATE_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid ML-KEM-768 private key length: " + mlKemPrivateKey.length);
		}

		encoded = new byte[HYBRID_AGREEMENT_PRIVATE_KEY_BYTES];
		System.arraycopy(x25519PrivateKey, 0, encoded, 0, 32);
		System.arraycopy(mlKemPrivateKey, 0, encoded, 32, mlKemPrivateKey.length);
	}

	@Override
	public String getKeyType() {
		return KEY_TYPE_HYBRID_AGREEMENT;
	}

	@Override
	public byte[] getEncoded() {
		return encoded;
	}

	/**
	 * Extracts the X25519 private key component.
	 *
	 * @return The X25519 private key (32 bytes)
	 */
	public byte[] getX25519PrivateKey() {
		byte[] x25519 = new byte[32];
		System.arraycopy(encoded, 0, x25519, 0, 32);
		return x25519;
	}

	/**
	 * Extracts the ML-KEM-768 private key component.
	 *
	 * @return The ML-KEM-768 private key (2,400 bytes)
	 */
	public byte[] getMlKemPrivateKey() {
		byte[] mlKem = new byte[ML_KEM_768_PRIVATE_KEY_BYTES];
		System.arraycopy(encoded, 32, mlKem, 0, ML_KEM_768_PRIVATE_KEY_BYTES);
		return mlKem;
	}

	/**
	 * Returns the X25519 component as an AgreementPrivateKey for legacy operations.
	 */
	public AgreementPrivateKey getX25519Component() {
		return new AgreementPrivateKey(getX25519PrivateKey());
	}

	/**
	 * Securely clears the private key material from memory.
	 * <p>
	 * This method should be called when the key is no longer needed to
	 * minimize the window during which the key is vulnerable to memory
	 * disclosure attacks.
	 */
	public void clear() {
		Arrays.fill(encoded, (byte) 0);
	}
}
