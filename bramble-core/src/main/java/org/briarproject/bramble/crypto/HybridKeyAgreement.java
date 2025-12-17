package org.briarproject.bramble.crypto;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.briarproject.bramble.api.crypto.HybridAgreementPrivateKey;
import org.briarproject.bramble.api.crypto.HybridAgreementPublicKey;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.util.ByteUtils;
import org.briarproject.bramble.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;
import org.whispersystems.curve25519.Curve25519;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.annotation.concurrent.Immutable;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SHARED_SECRET_LABEL;
import static org.briarproject.bramble.util.ByteUtils.INT_32_BYTES;

/**
 * Hybrid key agreement combining X25519 ECDH and ML-KEM-768 KEM.
 * <p>
 * This implementation provides defense-in-depth post-quantum security by
 * combining two independent cryptographic algorithms:
 * <ul>
 *   <li><b>X25519</b>: Elliptic Curve Diffie-Hellman with Curve25519</li>
 *   <li><b>ML-KEM-768</b>: NIST FIPS 203 post-quantum key encapsulation</li>
 * </ul>
 * <p>
 * The shared secret is derived by combining both component secrets using
 * BLAKE2b HKDF. An attacker must break BOTH algorithms to recover the
 * shared secret.
 * <p>
 * <b>Protocol Flow:</b>
 * <pre>
 * Alice (Initiator)                     Bob (Responder)
 *     │                                       │
 *     │  Generate hybrid keypair              │
 *     │    x25519_a = X25519.keygen()         │
 *     │    mlkem_a = MLKEM.keygen()           │
 *     │                                       │
 *     │  ───── hybrid_pub_a ─────────────────►│
 *     │                                       │
 *     │                   Generate hybrid keypair
 *     │                     x25519_b = X25519.keygen()
 *     │                     mlkem_b = MLKEM.keygen()
 *     │                                       │
 *     │                   Encapsulate to Alice:
 *     │                     (ct, ss_kem) = MLKEM.encaps(mlkem_pub_a)
 *     │                                       │
 *     │  ◄── hybrid_pub_b + kem_ciphertext ───│
 *     │                                       │
 *     │  Derive shared secrets:               │  Derive shared secrets:
 *     │    ss_ecdh = X25519(a_priv, b_pub)    │    ss_ecdh = X25519(b_priv, a_pub)
 *     │    ss_kem = MLKEM.decaps(ct, a_priv)  │    (ss_kem already computed)
 *     │                                       │
 *     │  Combined secret:                     │  Combined secret:
 *     │    ss = HKDF(ss_ecdh || ss_kem)       │    ss = HKDF(ss_ecdh || ss_kem)
 *     │                                       │
 * </pre>
 */
@NotNullByDefault
@Immutable
class HybridKeyAgreement {

	private final SecureRandom secureRandom;
	private final Curve25519 curve25519;
	private final MlKem768 mlKem768;

	HybridKeyAgreement(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
		this.curve25519 = Curve25519.getInstance("java");
		this.mlKem768 = new MlKem768(secureRandom);
	}

	/**
	 * Generates a new hybrid key pair for key agreement.
	 *
	 * @return A KeyPair containing hybrid public and private keys
	 */
	KeyPair generateKeyPair() {
		// Generate X25519 key pair
		org.whispersystems.curve25519.Curve25519KeyPair x25519KeyPair =
				curve25519.generateKeyPair();

		// Generate ML-KEM-768 key pair
		MlKem768.MlKemKeyPair mlKemKeyPair = mlKem768.generateKeyPair();

		// Combine into hybrid keys
		HybridAgreementPublicKey publicKey = new HybridAgreementPublicKey(
				x25519KeyPair.getPublicKey(),
				mlKemKeyPair.getPublicKey()
		);

		HybridAgreementPrivateKey privateKey = new HybridAgreementPrivateKey(
				x25519KeyPair.getPrivateKey(),
				mlKemKeyPair.getPrivateKey()
		);

		return new KeyPair(publicKey, privateKey);
	}

	/**
	 * Derives a shared secret from the local private key and remote public key.
	 * <p>
	 * This method performs both X25519 ECDH and ML-KEM decapsulation, then
	 * combines the results using BLAKE2b.
	 *
	 * @param label Unique label for domain separation
	 * @param theirPublicKey The remote party's hybrid public key
	 * @param ourKeyPair Our hybrid key pair
	 * @param kemCiphertext The ML-KEM ciphertext from the remote party
	 * @param inputs Additional inputs for key derivation
	 * @return The derived shared secret
	 * @throws GeneralSecurityException If key agreement fails
	 */
	SecretKey deriveSharedSecret(String label,
			HybridAgreementPublicKey theirPublicKey,
			KeyPair ourKeyPair,
			byte[] kemCiphertext,
			byte[]... inputs) throws GeneralSecurityException {

		HybridAgreementPrivateKey ourPrivateKey =
				(HybridAgreementPrivateKey) ourKeyPair.getPrivate();

		// 1. Perform X25519 ECDH
		byte[] x25519Secret = curve25519.calculateAgreement(
				theirPublicKey.getX25519PublicKey(),
				ourPrivateKey.getX25519PrivateKey()
		);

		// Validate X25519 shared secret (must not be all zeros)
		if (isAllZeros(x25519Secret)) {
			throw new GeneralSecurityException(
					"Invalid X25519 shared secret (all zeros - possible low-order point attack)");
		}

		// 2. Perform ML-KEM decapsulation
		byte[] kemSecret = mlKem768.decapsulate(
				ourPrivateKey.getMlKemPrivateKey(),
				kemCiphertext
		);

		// 3. Combine secrets using BLAKE2b
		SecretKey sharedSecret = combineSecrets(label, x25519Secret, kemSecret,
				theirPublicKey.getEncoded(),
				ourKeyPair.getPublic().getEncoded(),
				inputs);

		// Clear intermediate secrets
		Arrays.fill(x25519Secret, (byte) 0);
		Arrays.fill(kemSecret, (byte) 0);

		return sharedSecret;
	}

	/**
	 * Encapsulates a shared secret to the remote party's public key.
	 * <p>
	 * This is the initiator's operation when they don't have a KEM ciphertext
	 * from the responder yet.
	 *
	 * @param theirPublicKey The remote party's hybrid public key
	 * @return The encapsulation result containing ciphertext and partial secret
	 * @throws GeneralSecurityException If encapsulation fails
	 */
	HybridEncapsulation encapsulate(HybridAgreementPublicKey theirPublicKey)
			throws GeneralSecurityException {
		MlKem768.MlKemEncapsulation enc = mlKem768.encapsulate(
				theirPublicKey.getMlKemPublicKey()
		);
		return new HybridEncapsulation(enc.getCiphertext(), enc.getSharedSecret());
	}

	/**
	 * Derives a shared secret as the responder (who generated the KEM ciphertext).
	 * <p>
	 * The responder already has the KEM shared secret from encapsulation, so
	 * they only need to perform ECDH.
	 *
	 * @param label Unique label for domain separation
	 * @param theirPublicKey The initiator's hybrid public key
	 * @param ourKeyPair Our hybrid key pair
	 * @param kemSecret The KEM shared secret from our encapsulation
	 * @param inputs Additional inputs for key derivation
	 * @return The derived shared secret
	 * @throws GeneralSecurityException If key agreement fails
	 */
	SecretKey deriveSharedSecretAsResponder(String label,
			HybridAgreementPublicKey theirPublicKey,
			KeyPair ourKeyPair,
			byte[] kemSecret,
			byte[]... inputs) throws GeneralSecurityException {

		HybridAgreementPrivateKey ourPrivateKey =
				(HybridAgreementPrivateKey) ourKeyPair.getPrivate();

		// Perform X25519 ECDH
		byte[] x25519Secret = curve25519.calculateAgreement(
				theirPublicKey.getX25519PublicKey(),
				ourPrivateKey.getX25519PrivateKey()
		);

		// Validate X25519 shared secret
		if (isAllZeros(x25519Secret)) {
			throw new GeneralSecurityException(
					"Invalid X25519 shared secret (all zeros)");
		}

		// Combine secrets using BLAKE2b
		SecretKey sharedSecret = combineSecrets(label, x25519Secret, kemSecret,
				theirPublicKey.getEncoded(),
				ourKeyPair.getPublic().getEncoded(),
				inputs);

		// Clear intermediate secret
		Arrays.fill(x25519Secret, (byte) 0);

		return sharedSecret;
	}

	/**
	 * Combines X25519 and ML-KEM shared secrets using BLAKE2b.
	 * <p>
	 * The combination includes public keys to bind the secret to the
	 * specific key exchange instance. Public keys are added in canonical
	 * (lexicographic) order to ensure both parties compute the same hash.
	 */
	private SecretKey combineSecrets(String label,
			byte[] x25519Secret,
			byte[] kemSecret,
			byte[] theirPublicKey,
			byte[] ourPublicKey,
			byte[]... additionalInputs) {

		// Use BLAKE2b-256 for combining
		Blake2bDigest digest = new Blake2bDigest(256);

		// Add label with length prefix
		byte[] labelBytes = StringUtils.toUtf8(HYBRID_SHARED_SECRET_LABEL + "/" + label);
		byte[] length = new byte[INT_32_BYTES];
		ByteUtils.writeUint32(labelBytes.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(labelBytes, 0, labelBytes.length);

		// Add X25519 shared secret
		ByteUtils.writeUint32(x25519Secret.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(x25519Secret, 0, x25519Secret.length);

		// Add ML-KEM shared secret
		ByteUtils.writeUint32(kemSecret.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(kemSecret, 0, kemSecret.length);

		// Add public keys in canonical (lexicographic) order to ensure both
		// parties compute the same hash regardless of which key is "ours"
		byte[] firstKey, secondKey;
		if (compareBytes(ourPublicKey, theirPublicKey) < 0) {
			firstKey = ourPublicKey;
			secondKey = theirPublicKey;
		} else {
			firstKey = theirPublicKey;
			secondKey = ourPublicKey;
		}

		ByteUtils.writeUint32(firstKey.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(firstKey, 0, firstKey.length);

		ByteUtils.writeUint32(secondKey.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(secondKey, 0, secondKey.length);

		// Add any additional inputs
		for (byte[] input : additionalInputs) {
			ByteUtils.writeUint32(input.length, length, 0);
			digest.update(length, 0, length.length);
			digest.update(input, 0, input.length);
		}

		// Finalize
		byte[] output = new byte[SecretKey.LENGTH];
		digest.doFinal(output, 0);

		return new SecretKey(output);
	}

	/**
	 * Lexicographically compares two byte arrays.
	 * Returns negative if a < b, positive if a > b, zero if equal.
	 */
	private int compareBytes(byte[] a, byte[] b) {
		int minLen = Math.min(a.length, b.length);
		for (int i = 0; i < minLen; i++) {
			// Compare as unsigned bytes
			int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
			if (diff != 0) return diff;
		}
		return a.length - b.length;
	}

	/**
	 * Checks if a byte array is all zeros.
	 */
	private boolean isAllZeros(byte[] bytes) {
		int acc = 0;
		for (byte b : bytes) {
			acc |= b;
		}
		return acc == 0;
	}

	/**
	 * Result of hybrid KEM encapsulation.
	 */
	static class HybridEncapsulation {
		private final byte[] ciphertext;
		private final byte[] sharedSecret;

		HybridEncapsulation(byte[] ciphertext, byte[] sharedSecret) {
			this.ciphertext = ciphertext;
			this.sharedSecret = sharedSecret;
		}

		/**
		 * Returns the KEM ciphertext to send to the recipient.
		 */
		byte[] getCiphertext() {
			return ciphertext;
		}

		/**
		 * Returns the KEM shared secret (for use by the sender).
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
