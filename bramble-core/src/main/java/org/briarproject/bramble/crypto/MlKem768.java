package org.briarproject.bramble.crypto;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;
import org.briarproject.bramble.api.crypto.PostQuantumConstants;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.annotation.concurrent.Immutable;

/**
 * ML-KEM-768 (formerly Kyber-768) Key Encapsulation Mechanism.
 * <p>
 * This class wraps the BouncyCastle implementation of ML-KEM as standardized
 * in NIST FIPS 203. ML-KEM-768 provides NIST Level 3 security (equivalent to
 * AES-192), offering protection against both classical and quantum attacks.
 * <p>
 * <b>Security Properties:</b>
 * <ul>
 *   <li>IND-CCA2 secure (indistinguishable under chosen ciphertext attack)</li>
 *   <li>Based on Module Learning With Errors (MLWE) problem</li>
 *   <li>NIST Level 3 post-quantum security</li>
 * </ul>
 * <p>
 * <b>Key Sizes:</b>
 * <ul>
 *   <li>Public Key: 1,184 bytes</li>
 *   <li>Private Key: 2,400 bytes</li>
 *   <li>Ciphertext: 1,088 bytes</li>
 *   <li>Shared Secret: 32 bytes</li>
 * </ul>
 */
@NotNullByDefault
@Immutable
class MlKem768 {

	private final SecureRandom secureRandom;

	MlKem768(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
	}

	/**
	 * Generates a new ML-KEM-768 key pair.
	 *
	 * @return A key pair containing public and private keys
	 */
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

	/**
	 * Encapsulates a shared secret using the recipient's public key.
	 * <p>
	 * This is the sender's operation. The sender generates a random shared
	 * secret and encapsulates it using the recipient's public key. Only the
	 * recipient (with the corresponding private key) can recover the secret.
	 *
	 * @param publicKeyBytes The recipient's ML-KEM-768 public key (1,184 bytes)
	 * @return The encapsulation result containing ciphertext and shared secret
	 * @throws GeneralSecurityException If the public key is invalid
	 */
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

	/**
	 * Decapsulates a shared secret using the recipient's private key.
	 * <p>
	 * This is the recipient's operation. The recipient uses their private key
	 * to recover the shared secret from the ciphertext sent by the sender.
	 *
	 * @param privateKeyBytes The recipient's ML-KEM-768 private key (2,400 bytes)
	 * @param ciphertext The encapsulated ciphertext from the sender (1,088 bytes)
	 * @return The shared secret (32 bytes)
	 * @throws GeneralSecurityException If the keys or ciphertext are invalid
	 */
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

	/**
	 * Validates an ML-KEM-768 public key.
	 *
	 * @param publicKeyBytes The public key bytes to validate
	 * @return true if the key is valid, false otherwise
	 */
	boolean isValidPublicKey(byte[] publicKeyBytes) {
		if (publicKeyBytes.length != PostQuantumConstants.ML_KEM_768_PUBLIC_KEY_BYTES) {
			return false;
		}
		try {
			// Attempt to parse the key - will throw if invalid
			new MLKEMPublicKeyParameters(MLKEMParameters.ml_kem_768, publicKeyBytes);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * ML-KEM-768 key pair container.
	 */
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

		/**
		 * Securely clears the private key from memory.
		 */
		void clearPrivateKey() {
			Arrays.fill(privateKey, (byte) 0);
		}
	}

	/**
	 * ML-KEM-768 encapsulation result containing ciphertext and shared secret.
	 */
	static class MlKemEncapsulation {
		private final byte[] ciphertext;
		private final byte[] sharedSecret;

		MlKemEncapsulation(byte[] ciphertext, byte[] sharedSecret) {
			this.ciphertext = ciphertext;
			this.sharedSecret = sharedSecret;
		}

		/**
		 * Returns the ciphertext (1,088 bytes) to send to the recipient.
		 */
		byte[] getCiphertext() {
			return ciphertext;
		}

		/**
		 * Returns the shared secret (32 bytes).
		 */
		byte[] getSharedSecret() {
			return sharedSecret;
		}

		/**
		 * Securely clears the shared secret from memory.
		 */
		void clearSecret() {
			Arrays.fill(sharedSecret, (byte) 0);
		}
	}
}
