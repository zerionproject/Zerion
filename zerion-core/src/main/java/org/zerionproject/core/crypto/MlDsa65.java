package org.zerionproject.core.crypto;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.annotation.concurrent.Immutable;

@NotNullByDefault
@Immutable
class MlDsa65 {

	private final SecureRandom secureRandom;

	MlDsa65(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
	}

	MlDsaKeyPair generateKeyPair() {
		MLDSAKeyPairGenerator keyGen = new MLDSAKeyPairGenerator();
		keyGen.init(new MLDSAKeyGenerationParameters(secureRandom,
				MLDSAParameters.ml_dsa_65));

		AsymmetricCipherKeyPair keyPair = keyGen.generateKeyPair();

		MLDSAPublicKeyParameters publicKey =
				(MLDSAPublicKeyParameters) keyPair.getPublic();
		MLDSAPrivateKeyParameters privateKey =
				(MLDSAPrivateKeyParameters) keyPair.getPrivate();

		return new MlDsaKeyPair(publicKey.getEncoded(), privateKey.getEncoded());
	}

	byte[] sign(byte[] privateKeyBytes, byte[] message)
			throws GeneralSecurityException {
		if (privateKeyBytes.length != PostQuantumConstants.ML_DSA_65_PRIVATE_KEY_BYTES) {
			throw new GeneralSecurityException(
					"Invalid ML-DSA-65 private key length: " + privateKeyBytes.length);
		}

		MLDSAPrivateKeyParameters privateKey = new MLDSAPrivateKeyParameters(
				MLDSAParameters.ml_dsa_65, privateKeyBytes);

		try {
			MLDSASigner signer = new MLDSASigner();
			signer.init(true, privateKey);
			signer.update(message, 0, message.length);

			return signer.generateSignature();
		} catch (CryptoException e) {
			throw new GeneralSecurityException("ML-DSA-65 signing failed", e);
		}
	}

	boolean verify(byte[] publicKeyBytes, byte[] message, byte[] signature)
			throws GeneralSecurityException {
		if (publicKeyBytes.length != PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES) {
			throw new GeneralSecurityException(
					"Invalid ML-DSA-65 public key length: " + publicKeyBytes.length);
		}
		if (signature.length != PostQuantumConstants.ML_DSA_65_SIGNATURE_BYTES) {
			return false;
		}

		try {
			MLDSAPublicKeyParameters publicKey = new MLDSAPublicKeyParameters(
					MLDSAParameters.ml_dsa_65, publicKeyBytes);

			MLDSASigner verifier = new MLDSASigner();
			verifier.init(false, publicKey);
			verifier.update(message, 0, message.length);

			return verifier.verifySignature(signature);
		} catch (Exception e) {
			return false;
		}
	}

	boolean isValidPublicKey(byte[] publicKeyBytes) {
		if (publicKeyBytes.length != PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES) {
			return false;
		}
		try {
			new MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, publicKeyBytes);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	boolean isValidPrivateKey(byte[] privateKeyBytes) {
		if (privateKeyBytes.length != PostQuantumConstants.ML_DSA_65_PRIVATE_KEY_BYTES) {
			return false;
		}
		try {
			new MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65, privateKeyBytes);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	static class MlDsaKeyPair {
		private final byte[] publicKey;
		private final byte[] privateKey;

		MlDsaKeyPair(byte[] publicKey, byte[] privateKey) {
			this.publicKey = publicKey;
			this.privateKey = privateKey;
		}

		byte[] getPublicKey() {
			return publicKey;
		}

		byte[] getPrivateKey() {
			return privateKey;
		}

		void clearPrivateKey() {
			Arrays.fill(privateKey, (byte) 0);
		}
	}
}
