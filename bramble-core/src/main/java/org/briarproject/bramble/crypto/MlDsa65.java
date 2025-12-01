package org.briarproject.bramble.crypto;

import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSAPublicKeyParameters;
import org.bouncycastle.pqc.crypto.mldsa.MLDSASigner;
import org.briarproject.bramble.api.crypto.PostQuantumConstants;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.logging.Logger;

import javax.annotation.concurrent.Immutable;

import static java.util.logging.Level.INFO;
import static java.util.logging.Logger.getLogger;

/**
 * ML-DSA-65 (formerly Dilithium-III) Digital Signature Algorithm.
 * <p>
 * This class wraps the BouncyCastle implementation of ML-DSA as standardized
 * in NIST FIPS 204. ML-DSA-65 provides NIST Level 3 security (equivalent to
 * AES-192), offering protection against both classical and quantum attacks.
 * <p>
 * <b>Security Properties:</b>
 * <ul>
 *   <li>EUF-CMA secure (existentially unforgeable under chosen message attack)</li>
 *   <li>Based on Module Learning With Errors (MLWE) and Module-SIS problems</li>
 *   <li>NIST Level 3 post-quantum security</li>
 * </ul>
 * <p>
 * <b>Key Sizes:</b>
 * <ul>
 *   <li>Public Key: 1,952 bytes</li>
 *   <li>Private Key: 4,032 bytes</li>
 *   <li>Signature: 3,293 bytes</li>
 * </ul>
 */
@NotNullByDefault
@Immutable
class MlDsa65 {

	private static final Logger LOG = getLogger(MlDsa65.class.getName());

	private final SecureRandom secureRandom;

	MlDsa65(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
		if (LOG.isLoggable(INFO)) {
			LOG.info("ML-DSA-65 initialized (NIST FIPS 204)");
		}
	}

	/**
	 * Generates a new ML-DSA-65 key pair.
	 *
	 * @return A key pair containing public and private keys
	 */
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

	/**
	 * Signs a message using the ML-DSA-65 private key.
	 *
	 * @param privateKeyBytes The signer's private key (4,032 bytes)
	 * @param message The message to sign
	 * @return The signature (3,293 bytes)
	 * @throws GeneralSecurityException If the private key is invalid
	 */
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

	/**
	 * Verifies a signature using the ML-DSA-65 public key.
	 *
	 * @param publicKeyBytes The signer's public key (1,952 bytes)
	 * @param message The signed message
	 * @param signature The signature to verify (3,293 bytes)
	 * @return true if the signature is valid, false otherwise
	 * @throws GeneralSecurityException If the public key is invalid
	 */
	boolean verify(byte[] publicKeyBytes, byte[] message, byte[] signature)
			throws GeneralSecurityException {
		if (publicKeyBytes.length != PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES) {
			throw new GeneralSecurityException(
					"Invalid ML-DSA-65 public key length: " + publicKeyBytes.length);
		}
		if (signature.length != PostQuantumConstants.ML_DSA_65_SIGNATURE_BYTES) {
			// Signature length mismatch - not necessarily an error, could be
			// a different algorithm or truncated data
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
			// Invalid key or signature format
			return false;
		}
	}

	/**
	 * Validates an ML-DSA-65 public key.
	 *
	 * @param publicKeyBytes The public key bytes to validate
	 * @return true if the key is valid, false otherwise
	 */
	boolean isValidPublicKey(byte[] publicKeyBytes) {
		if (publicKeyBytes.length != PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES) {
			return false;
		}
		try {
			// Attempt to parse the key - will throw if invalid
			new MLDSAPublicKeyParameters(MLDSAParameters.ml_dsa_65, publicKeyBytes);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Validates an ML-DSA-65 private key.
	 *
	 * @param privateKeyBytes The private key bytes to validate
	 * @return true if the key is valid, false otherwise
	 */
	boolean isValidPrivateKey(byte[] privateKeyBytes) {
		if (privateKeyBytes.length != PostQuantumConstants.ML_DSA_65_PRIVATE_KEY_BYTES) {
			return false;
		}
		try {
			// Attempt to parse the key - will throw if invalid
			new MLDSAPrivateKeyParameters(MLDSAParameters.ml_dsa_65, privateKeyBytes);
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * ML-DSA-65 key pair container.
	 */
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

		/**
		 * Securely clears the private key from memory.
		 */
		void clearPrivateKey() {
			Arrays.fill(privateKey, (byte) 0);
		}
	}
}
