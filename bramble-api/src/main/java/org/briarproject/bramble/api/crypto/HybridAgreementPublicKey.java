package org.briarproject.bramble.api.crypto;

import org.briarproject.bramble.api.Bytes;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_AGREEMENT;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.ML_KEM_768_PUBLIC_KEY_BYTES;

/**
 * Hybrid agreement public key combining X25519 and ML-KEM-768.
 * <p>
 * This key provides post-quantum security through the hybrid combination
 * of classical ECDH (X25519) and post-quantum KEM (ML-KEM-768). An attacker
 * must break BOTH algorithms to compromise the shared secret.
 * <p>
 * <b>Key Structure (1,216 bytes):</b>
 * <pre>
 * ┌────────────────────┬────────────────────────┐
 * │ X25519 (32 bytes)  │ ML-KEM-768 (1,184 bytes) │
 * └────────────────────┴────────────────────────┘
 * </pre>
 * <p>
 * Note: Extends {@link Bytes} for compatibility with BDF serialization.
 */
@Immutable
@NotNullByDefault
public class HybridAgreementPublicKey extends Bytes implements PublicKey {

	/**
	 * Creates a hybrid public key from the combined encoded form.
	 *
	 * @param encoded The combined key bytes (1,216 bytes)
	 * @throws IllegalArgumentException If the key length is incorrect
	 */
	public HybridAgreementPublicKey(byte[] encoded) {
		super(encoded);
		if (encoded.length != HYBRID_AGREEMENT_PUBLIC_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid hybrid agreement public key length: " + encoded.length +
							", expected: " + HYBRID_AGREEMENT_PUBLIC_KEY_BYTES);
		}
	}

	/**
	 * Creates a hybrid public key from separate X25519 and ML-KEM-768 keys.
	 *
	 * @param x25519PublicKey The X25519 public key (32 bytes)
	 * @param mlKemPublicKey The ML-KEM-768 public key (1,184 bytes)
	 * @throws IllegalArgumentException If key lengths are incorrect
	 */
	public HybridAgreementPublicKey(byte[] x25519PublicKey, byte[] mlKemPublicKey) {
		super(combineKeys(x25519PublicKey, mlKemPublicKey));
	}

	private static byte[] combineKeys(byte[] x25519PublicKey, byte[] mlKemPublicKey) {
		if (x25519PublicKey.length != 32) {
			throw new IllegalArgumentException(
					"Invalid X25519 public key length: " + x25519PublicKey.length);
		}
		if (mlKemPublicKey.length != ML_KEM_768_PUBLIC_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid ML-KEM-768 public key length: " + mlKemPublicKey.length);
		}

		byte[] encoded = new byte[HYBRID_AGREEMENT_PUBLIC_KEY_BYTES];
		System.arraycopy(x25519PublicKey, 0, encoded, 0, 32);
		System.arraycopy(mlKemPublicKey, 0, encoded, 32, mlKemPublicKey.length);
		return encoded;
	}

	@Override
	public String getKeyType() {
		return KEY_TYPE_HYBRID_AGREEMENT;
	}

	@Override
	public byte[] getEncoded() {
		return getBytes();
	}

	/**
	 * Extracts the X25519 public key component.
	 *
	 * @return The X25519 public key (32 bytes)
	 */
	public byte[] getX25519PublicKey() {
		byte[] x25519 = new byte[32];
		System.arraycopy(getBytes(), 0, x25519, 0, 32);
		return x25519;
	}

	/**
	 * Extracts the ML-KEM-768 public key component.
	 *
	 * @return The ML-KEM-768 public key (1,184 bytes)
	 */
	public byte[] getMlKemPublicKey() {
		byte[] mlKem = new byte[ML_KEM_768_PUBLIC_KEY_BYTES];
		System.arraycopy(getBytes(), 32, mlKem, 0, ML_KEM_768_PUBLIC_KEY_BYTES);
		return mlKem;
	}

	/**
	 * Returns the X25519 component as an AgreementPublicKey for legacy operations.
	 */
	public AgreementPublicKey getX25519Component() {
		return new AgreementPublicKey(getX25519PublicKey());
	}
}
