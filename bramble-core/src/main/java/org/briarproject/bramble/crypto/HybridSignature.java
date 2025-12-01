package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.HybridSignaturePrivateKey;
import org.briarproject.bramble.api.crypto.HybridSignaturePublicKey;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.SignaturePrivateKey;
import org.briarproject.bramble.api.crypto.SignaturePublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.logging.Logger;

import javax.annotation.concurrent.Immutable;

import static java.util.logging.Level.INFO;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.ML_DSA_65_SIGNATURE_BYTES;

/**
 * Hybrid digital signature combining Ed25519 and ML-DSA-65.
 * <p>
 * This implementation provides defense-in-depth post-quantum security by
 * combining two independent signature algorithms:
 * <ul>
 *   <li><b>Ed25519</b>: Edwards-curve Digital Signature Algorithm</li>
 *   <li><b>ML-DSA-65</b>: NIST FIPS 204 post-quantum digital signature</li>
 * </ul>
 * <p>
 * Both signatures are required for verification - an attacker must forge
 * BOTH signatures to successfully attack the scheme.
 * <p>
 * <b>Signature Structure (3,357 bytes):</b>
 * <pre>
 * ┌───────────────────┬─────────────────────────┐
 * │ Ed25519 (64 bytes) │ ML-DSA-65 (3,293 bytes) │
 * └───────────────────┴─────────────────────────┘
 * </pre>
 */
@NotNullByDefault
@Immutable
class HybridSignature {

	private static final Logger LOG = getLogger(HybridSignature.class.getName());
	private static final int SIGNATURE_KEY_PAIR_BITS = 256;
	private static final EdDSAParameterSpec ED25519_SPEC =
			EdDSANamedCurveTable.getByName("Ed25519");

	private final SecureRandom secureRandom;
	private final KeyPairGenerator ed25519KeyPairGenerator;
	private final MlDsa65 mlDsa65;

	HybridSignature(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
		this.ed25519KeyPairGenerator = new KeyPairGenerator();
		this.ed25519KeyPairGenerator.initialize(SIGNATURE_KEY_PAIR_BITS, secureRandom);
		this.mlDsa65 = new MlDsa65(secureRandom);
		if (LOG.isLoggable(INFO)) {
			LOG.info("Hybrid signature initialized (Ed25519 + ML-DSA-65)");
		}
	}

	/**
	 * Generates a new hybrid key pair for digital signatures.
	 *
	 * @return A KeyPair containing hybrid public and private keys
	 */
	KeyPair generateKeyPair() {
		// Generate Ed25519 key pair
		java.security.KeyPair ed25519KeyPair = ed25519KeyPairGenerator.generateKeyPair();
		EdDSAPublicKey edPublicKey = (EdDSAPublicKey) ed25519KeyPair.getPublic();
		EdDSAPrivateKey edPrivateKey = (EdDSAPrivateKey) ed25519KeyPair.getPrivate();

		// Generate ML-DSA-65 key pair
		MlDsa65.MlDsaKeyPair mlDsaKeyPair = mlDsa65.generateKeyPair();

		// Combine into hybrid keys
		HybridSignaturePublicKey publicKey = new HybridSignaturePublicKey(
				edPublicKey.getAbyte(),
				mlDsaKeyPair.getPublicKey()
		);

		HybridSignaturePrivateKey privateKey = new HybridSignaturePrivateKey(
				edPrivateKey.getSeed(),
				mlDsaKeyPair.getPrivateKey()
		);

		return new KeyPair(publicKey, privateKey);
	}

	/**
	 * Signs a message using the hybrid private key.
	 * <p>
	 * Both Ed25519 and ML-DSA-65 signatures are computed and concatenated.
	 *
	 * @param message The message to sign
	 * @param privateKey The hybrid private key
	 * @return The hybrid signature (3,357 bytes)
	 * @throws GeneralSecurityException If signing fails
	 */
	byte[] sign(byte[] message, HybridSignaturePrivateKey privateKey)
			throws GeneralSecurityException {
		// Sign with Ed25519
		byte[] ed25519Signature = signEd25519(message, privateKey.getEd25519PrivateKey());

		// Sign with ML-DSA-65
		byte[] mlDsaSignature = mlDsa65.sign(privateKey.getMlDsaPrivateKey(), message);

		// Combine signatures
		byte[] hybridSignature = new byte[HYBRID_SIGNATURE_BYTES];
		System.arraycopy(ed25519Signature, 0, hybridSignature, 0, 64);
		System.arraycopy(mlDsaSignature, 0, hybridSignature, 64, mlDsaSignature.length);

		return hybridSignature;
	}

	/**
	 * Verifies a hybrid signature.
	 * <p>
	 * Both Ed25519 and ML-DSA-65 signatures must be valid for the
	 * verification to succeed.
	 *
	 * @param signature The hybrid signature to verify
	 * @param message The signed message
	 * @param publicKey The signer's hybrid public key
	 * @return true if BOTH signatures are valid, false otherwise
	 * @throws GeneralSecurityException If verification fails due to invalid keys
	 */
	boolean verify(byte[] signature, byte[] message, HybridSignaturePublicKey publicKey)
			throws GeneralSecurityException {
		// Validate signature length
		if (signature.length != HYBRID_SIGNATURE_BYTES) {
			return false;
		}

		// Extract component signatures
		byte[] ed25519Signature = new byte[64];
		byte[] mlDsaSignature = new byte[ML_DSA_65_SIGNATURE_BYTES];
		System.arraycopy(signature, 0, ed25519Signature, 0, 64);
		System.arraycopy(signature, 64, mlDsaSignature, 0, ML_DSA_65_SIGNATURE_BYTES);

		// Verify Ed25519 signature
		boolean ed25519Valid = verifyEd25519(ed25519Signature, message,
				publicKey.getEd25519PublicKey());

		// Verify ML-DSA-65 signature
		boolean mlDsaValid = mlDsa65.verify(publicKey.getMlDsaPublicKey(),
				message, mlDsaSignature);

		// Both must be valid
		return ed25519Valid && mlDsaValid;
	}

	/**
	 * Signs with Ed25519 using the i2p.crypto library.
	 */
	private byte[] signEd25519(byte[] message, byte[] privateKeySeed)
			throws GeneralSecurityException {
		EdSignature signature = new EdSignature();
		signature.initSign(new SignaturePrivateKey(privateKeySeed));
		signature.update(message);
		return signature.sign();
	}

	/**
	 * Verifies an Ed25519 signature using the i2p.crypto library.
	 */
	private boolean verifyEd25519(byte[] signature, byte[] message, byte[] publicKeyBytes)
			throws GeneralSecurityException {
		try {
			EdSignature verifier = new EdSignature();
			verifier.initVerify(new SignaturePublicKey(publicKeyBytes));
			verifier.update(message);
			return verifier.verify(signature);
		} catch (Exception e) {
			// Invalid signature format
			return false;
		}
	}

	/**
	 * Validates a hybrid signature public key.
	 *
	 * @param publicKey The public key to validate
	 * @return true if both component keys are valid
	 */
	boolean isValidPublicKey(HybridSignaturePublicKey publicKey) {
		// Validate Ed25519 public key
		try {
			new EdDSAPublicKeySpec(publicKey.getEd25519PublicKey(), ED25519_SPEC);
		} catch (Exception e) {
			return false;
		}

		// Validate ML-DSA-65 public key
		return mlDsa65.isValidPublicKey(publicKey.getMlDsaPublicKey());
	}
}
