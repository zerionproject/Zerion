package org.zerionproject.core.crypto;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.annotation.concurrent.Immutable;

@NotNullByDefault
@Immutable
class MlKem768 {

	private final SecureRandom secureRandom;

	MlKem768(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
	}

	MlKemKeyPair generateKeyPair() {
		MLKEMKeyPairGenerator keyGen = new MLKEMKeyPairGenerator();
		keyGen.init(new MLKEMKeyGenerationParameters(secureRandom,
				MLKEMParameters.ml_kem_768));

		AsymmetricCipherKeyPair keyPair = keyGen.generateKeyPair();

		MLKEMPublicKeyParameters publicKey =
				(MLKEMPublicKeyParameters) keyPair.getPublic();
		MLKEMPrivateKeyParameters privateKey =
				(MLKEMPrivateKeyParameters) keyPair.getPrivate();

		return new MlKemKeyPair(publicKey.getEncoded(), privateKey.getEncoded());
	}

	MlKemEncapsulation encapsulate(byte[] publicKeyBytes)
			throws GeneralSecurityException {
		if (publicKeyBytes.length != PostQuantumConstants.ML_KEM_768_PUBLIC_KEY_BYTES) {
			throw new GeneralSecurityException(
					"Invalid ML-KEM-768 public key length: " + publicKeyBytes.length);
		}

		MLKEMPublicKeyParameters publicKey = new MLKEMPublicKeyParameters(
				MLKEMParameters.ml_kem_768, publicKeyBytes);

		MLKEMGenerator encapsulator = new MLKEMGenerator(secureRandom);
		SecretWithEncapsulation enc = encapsulator.generateEncapsulated(publicKey);

		byte[] ciphertext = enc.getEncapsulation();
		byte[] sharedSecret = enc.getSecret();

		return new MlKemEncapsulation(ciphertext, sharedSecret);
	}

	byte[] decapsulate(byte[] privateKeyBytes, byte[] ciphertext)
			throws GeneralSecurityException {
		if (privateKeyBytes.length != PostQuantumConstants.ML_KEM_768_PRIVATE_KEY_BYTES) {
			throw new GeneralSecurityException(
					"Invalid ML-KEM-768 private key length: " + privateKeyBytes.length);
		}
		if (ciphertext.length != PostQuantumConstants.ML_KEM_768_CIPHERTEXT_BYTES) {
			throw new GeneralSecurityException(
					"Invalid ML-KEM-768 ciphertext length: " + ciphertext.length);
		}

		MLKEMPrivateKeyParameters privateKey = new MLKEMPrivateKeyParameters(
				MLKEMParameters.ml_kem_768, privateKeyBytes);

		MLKEMExtractor extractor = new MLKEMExtractor(privateKey);
		return extractor.extractSecret(ciphertext);
	}

	boolean isValidPublicKey(byte[] publicKeyBytes) {
		if (publicKeyBytes.length != PostQuantumConstants.ML_KEM_768_PUBLIC_KEY_BYTES) {
			return false;
		}
		try {
			new MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, publicKeyBytes);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	static class MlKemKeyPair {
		private final byte[] publicKey;
		private final byte[] privateKey;

		MlKemKeyPair(byte[] publicKey, byte[] privateKey) {
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

	static class MlKemEncapsulation {
		private final byte[] ciphertext;
		private final byte[] sharedSecret;

		MlKemEncapsulation(byte[] ciphertext, byte[] sharedSecret) {
			this.ciphertext = ciphertext;
			this.sharedSecret = sharedSecret;
		}

		byte[] getCiphertext() {
			return ciphertext;
		}

		byte[] getSharedSecret() {
			return sharedSecret;
		}

		void clearSecret() {
			Arrays.fill(sharedSecret, (byte) 0);
		}
	}
}
