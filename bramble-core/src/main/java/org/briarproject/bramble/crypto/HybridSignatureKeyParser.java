package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.HybridSignaturePrivateKey;
import org.briarproject.bramble.api.crypto.HybridSignaturePublicKey;
import org.briarproject.bramble.api.crypto.KeyParser;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PRIVATE_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES;

/**
 * Parser for hybrid signature keys (Ed25519 + ML-DSA-65).
 */
@NotNullByDefault
class HybridSignatureKeyParser implements KeyParser {

	private final MlDsa65 mlDsa65;

	HybridSignatureKeyParser(MlDsa65 mlDsa65) {
		this.mlDsa65 = mlDsa65;
	}

	@Override
	public PublicKey parsePublicKey(byte[] encodedKey)
			throws GeneralSecurityException {
		if (encodedKey.length != HYBRID_SIGNATURE_PUBLIC_KEY_BYTES) {
			throw new GeneralSecurityException(
					"Invalid hybrid signature public key length: " +
							encodedKey.length + ", expected: " +
							HYBRID_SIGNATURE_PUBLIC_KEY_BYTES);
		}

		// Validate Ed25519 component (32 bytes at start)
		// Ed25519 public keys need to be on the curve - basic length check here

		// Validate ML-DSA-65 component (1952 bytes after Ed25519)
		byte[] mlDsaPubKey = new byte[1952];
		System.arraycopy(encodedKey, 32, mlDsaPubKey, 0, 1952);
		if (!mlDsa65.isValidPublicKey(mlDsaPubKey)) {
			throw new GeneralSecurityException(
					"Invalid ML-DSA-65 public key component");
		}

		return new HybridSignaturePublicKey(encodedKey);
	}

	@Override
	public PrivateKey parsePrivateKey(byte[] encodedKey)
			throws GeneralSecurityException {
		if (encodedKey.length != HYBRID_SIGNATURE_PRIVATE_KEY_BYTES) {
			throw new GeneralSecurityException(
					"Invalid hybrid signature private key length: " +
							encodedKey.length + ", expected: " +
							HYBRID_SIGNATURE_PRIVATE_KEY_BYTES);
		}

		// Validate ML-DSA-65 private key component
		byte[] mlDsaPrivKey = new byte[4032];
		System.arraycopy(encodedKey, 32, mlDsaPrivKey, 0, 4032);
		if (!mlDsa65.isValidPrivateKey(mlDsaPrivKey)) {
			throw new GeneralSecurityException(
					"Invalid ML-DSA-65 private key component");
		}

		return new HybridSignaturePrivateKey(encodedKey);
	}
}
