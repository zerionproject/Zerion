package org.briarproject.bramble.api.crypto;

import org.briarproject.bramble.api.Bytes;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_SIGNATURE;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES;

/**
 * Hybrid signature public key combining Ed25519 and ML-DSA-65.
 * <p>
 * This key provides post-quantum security through the hybrid combination
 * of classical EdDSA (Ed25519) and post-quantum signatures (ML-DSA-65).
 * An attacker must break BOTH algorithms to forge a signature.
 * <p>
 * <b>Key Structure (1,984 bytes):</b>
 * <pre>
 * ┌────────────────────┬────────────────────────┐
 * │ Ed25519 (32 bytes) │ ML-DSA-65 (1,952 bytes) │
 * └────────────────────┴────────────────────────┘
 * </pre>
 * <p>
 * Note: Extends {@link Bytes} for compatibility with BDF serialization.
 */
@Immutable
@NotNullByDefault
public class HybridSignaturePublicKey extends Bytes implements PublicKey {

	/**
	 * Creates a hybrid public key from the combined encoded form.
	 *
	 * @param encoded The combined key bytes (1,984 bytes)
	 * @throws IllegalArgumentException If the key length is incorrect
	 */
	public HybridSignaturePublicKey(byte[] encoded) {
		super(encoded);
		if (encoded.length != HYBRID_SIGNATURE_PUBLIC_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid hybrid signature public key length: " + encoded.length +
							", expected: " + HYBRID_SIGNATURE_PUBLIC_KEY_BYTES);
		}
	}

	/**
	 * Creates a hybrid public key from separate Ed25519 and ML-DSA-65 keys.
	 *
	 * @param ed25519PublicKey The Ed25519 public key (32 bytes)
	 * @param mlDsaPublicKey The ML-DSA-65 public key (1,952 bytes)
	 * @throws IllegalArgumentException If key lengths are incorrect
	 */
	public HybridSignaturePublicKey(byte[] ed25519PublicKey, byte[] mlDsaPublicKey) {
		super(combineKeys(ed25519PublicKey, mlDsaPublicKey));
	}

	private static byte[] combineKeys(byte[] ed25519PublicKey, byte[] mlDsaPublicKey) {
		if (ed25519PublicKey.length != 32) {
			throw new IllegalArgumentException(
					"Invalid Ed25519 public key length: " + ed25519PublicKey.length);
		}
		if (mlDsaPublicKey.length != ML_DSA_65_PUBLIC_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid ML-DSA-65 public key length: " + mlDsaPublicKey.length);
		}

		byte[] encoded = new byte[HYBRID_SIGNATURE_PUBLIC_KEY_BYTES];
		System.arraycopy(ed25519PublicKey, 0, encoded, 0, 32);
		System.arraycopy(mlDsaPublicKey, 0, encoded, 32, mlDsaPublicKey.length);
		return encoded;
	}

	@Override
	public String getKeyType() {
		return KEY_TYPE_HYBRID_SIGNATURE;
	}

	@Override
	public byte[] getEncoded() {
		return getBytes();
	}

	/**
	 * Extracts the Ed25519 public key component.
	 *
	 * @return The Ed25519 public key (32 bytes)
	 */
	public byte[] getEd25519PublicKey() {
		byte[] ed25519 = new byte[32];
		System.arraycopy(getBytes(), 0, ed25519, 0, 32);
		return ed25519;
	}

	/**
	 * Extracts the ML-DSA-65 public key component.
	 *
	 * @return The ML-DSA-65 public key (1,952 bytes)
	 */
	public byte[] getMlDsaPublicKey() {
		byte[] mlDsa = new byte[ML_DSA_65_PUBLIC_KEY_BYTES];
		System.arraycopy(getBytes(), 32, mlDsa, 0, ML_DSA_65_PUBLIC_KEY_BYTES);
		return mlDsa;
	}

	/**
	 * Returns the Ed25519 component as a SignaturePublicKey for legacy operations.
	 */
	public SignaturePublicKey getEd25519Component() {
		return new SignaturePublicKey(getEd25519PublicKey());
	}
}
