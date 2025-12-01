package org.briarproject.bramble.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PRIVATE_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_SIGNATURE;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.ML_DSA_65_PRIVATE_KEY_BYTES;

/**
 * Hybrid signature private key combining Ed25519 and ML-DSA-65.
 * <p>
 * This key provides post-quantum security through the hybrid combination
 * of classical EdDSA (Ed25519) and post-quantum signatures (ML-DSA-65).
 * <p>
 * <b>Key Structure (4,064 bytes):</b>
 * <pre>
 * ┌────────────────────┬─────────────────────────┐
 * │ Ed25519 (32 bytes) │ ML-DSA-65 (4,032 bytes)  │
 * └────────────────────┴─────────────────────────┘
 * </pre>
 * <p>
 * <b>Security Note:</b> This class provides a {@link #clear()} method to
 * securely erase the private key from memory when no longer needed.
 */
@Immutable
@NotNullByDefault
public class HybridSignaturePrivateKey implements PrivateKey {

	private final byte[] encoded;

	/**
	 * Creates a hybrid private key from the combined encoded form.
	 *
	 * @param encoded The combined key bytes (4,064 bytes)
	 * @throws IllegalArgumentException If the key length is incorrect
	 */
	public HybridSignaturePrivateKey(byte[] encoded) {
		if (encoded.length != HYBRID_SIGNATURE_PRIVATE_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid hybrid signature private key length: " + encoded.length +
							", expected: " + HYBRID_SIGNATURE_PRIVATE_KEY_BYTES);
		}
		this.encoded = encoded;
	}

	/**
	 * Creates a hybrid private key from separate Ed25519 and ML-DSA-65 keys.
	 *
	 * @param ed25519PrivateKey The Ed25519 private key/seed (32 bytes)
	 * @param mlDsaPrivateKey The ML-DSA-65 private key (4,032 bytes)
	 * @throws IllegalArgumentException If key lengths are incorrect
	 */
	public HybridSignaturePrivateKey(byte[] ed25519PrivateKey, byte[] mlDsaPrivateKey) {
		if (ed25519PrivateKey.length != 32) {
			throw new IllegalArgumentException(
					"Invalid Ed25519 private key length: " + ed25519PrivateKey.length);
		}
		if (mlDsaPrivateKey.length != ML_DSA_65_PRIVATE_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid ML-DSA-65 private key length: " + mlDsaPrivateKey.length);
		}

		encoded = new byte[HYBRID_SIGNATURE_PRIVATE_KEY_BYTES];
		System.arraycopy(ed25519PrivateKey, 0, encoded, 0, 32);
		System.arraycopy(mlDsaPrivateKey, 0, encoded, 32, mlDsaPrivateKey.length);
	}

	@Override
	public String getKeyType() {
		return KEY_TYPE_HYBRID_SIGNATURE;
	}

	@Override
	public byte[] getEncoded() {
		return encoded;
	}

	/**
	 * Extracts the Ed25519 private key component (seed format).
	 *
	 * @return The Ed25519 private key/seed (32 bytes)
	 */
	public byte[] getEd25519PrivateKey() {
		byte[] ed25519 = new byte[32];
		System.arraycopy(encoded, 0, ed25519, 0, 32);
		return ed25519;
	}

	/**
	 * Extracts the ML-DSA-65 private key component.
	 *
	 * @return The ML-DSA-65 private key (4,032 bytes)
	 */
	public byte[] getMlDsaPrivateKey() {
		byte[] mlDsa = new byte[ML_DSA_65_PRIVATE_KEY_BYTES];
		System.arraycopy(encoded, 32, mlDsa, 0, ML_DSA_65_PRIVATE_KEY_BYTES);
		return mlDsa;
	}

	/**
	 * Returns the Ed25519 component as a SignaturePrivateKey for legacy operations.
	 */
	public SignaturePrivateKey getEd25519Component() {
		return new SignaturePrivateKey(getEd25519PrivateKey());
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
