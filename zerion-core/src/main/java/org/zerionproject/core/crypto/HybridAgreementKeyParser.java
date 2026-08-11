package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PRIVATE_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;

@NotNullByDefault
class HybridAgreementKeyParser implements KeyParser {

	private final MlKem768 mlKem768;

	HybridAgreementKeyParser(MlKem768 mlKem768) {
		this.mlKem768 = mlKem768;
	}

	@Override
	public PublicKey parsePublicKey(byte[] encodedKey)
			throws GeneralSecurityException {
		if (encodedKey.length != HYBRID_AGREEMENT_PUBLIC_KEY_BYTES) {
			throw new GeneralSecurityException(
					"Invalid hybrid agreement public key length: " +
							encodedKey.length + ", expected: " +
							HYBRID_AGREEMENT_PUBLIC_KEY_BYTES);
		}
		byte[] mlKemPubKey = new byte[1184];
		System.arraycopy(encodedKey, 32, mlKemPubKey, 0, 1184);
		if (!mlKem768.isValidPublicKey(mlKemPubKey)) {
			throw new GeneralSecurityException(
					"Invalid ML-KEM-768 public key component");
		}

		return new HybridAgreementPublicKey(encodedKey);
	}

	@Override
	public PrivateKey parsePrivateKey(byte[] encodedKey)
			throws GeneralSecurityException {
		if (encodedKey.length != HYBRID_AGREEMENT_PRIVATE_KEY_BYTES) {
			throw new GeneralSecurityException(
					"Invalid hybrid agreement private key length: " +
							encodedKey.length + ", expected: " +
							HYBRID_AGREEMENT_PRIVATE_KEY_BYTES);
		}
		return new HybridAgreementPrivateKey(encodedKey);
	}
}
