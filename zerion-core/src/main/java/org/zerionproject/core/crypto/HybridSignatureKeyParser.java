package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PRIVATE_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES;

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
		byte[] mlDsaPrivKey = new byte[4032];
		System.arraycopy(encodedKey, 32, mlDsaPrivKey, 0, 4032);
		if (!mlDsa65.isValidPrivateKey(mlDsaPrivKey)) {
			throw new GeneralSecurityException(
					"Invalid ML-DSA-65 private key component");
		}

		return new HybridSignaturePrivateKey(encodedKey);
	}
}
