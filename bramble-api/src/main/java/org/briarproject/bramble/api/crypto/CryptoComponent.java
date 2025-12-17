package org.briarproject.bramble.api.crypto;

import org.briarproject.bramble.api.UniqueId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.annotation.Nullable;

@NotNullByDefault
public interface CryptoComponent {

	UniqueId generateUniqueId();

	SecretKey generateSecretKey();

	SecureRandom getSecureRandom();

	// ==================== Classical Key Operations ====================

	KeyPair generateAgreementKeyPair();

	KeyParser getAgreementKeyParser();

	KeyPair generateSignatureKeyPair();

	KeyParser getSignatureKeyParser();

	KeyParser getMessageKeyParser();

	// ==================== Hybrid Post-Quantum Key Operations ====================

	/**
	 * Generates a hybrid key pair for key agreement (X25519 + ML-KEM-768).
	 * <p>
	 * The resulting keys provide post-quantum security through the ML-KEM-768
	 * component while maintaining classical security through X25519. Both
	 * algorithms must be broken to compromise the key exchange.
	 *
	 * @return A KeyPair containing HybridAgreementPublicKey and HybridAgreementPrivateKey
	 */
	KeyPair generateHybridAgreementKeyPair();

	/**
	 * Returns a parser for hybrid agreement keys.
	 *
	 * @return A KeyParser that can parse HybridAgreementPublicKey and HybridAgreementPrivateKey
	 */
	KeyParser getHybridAgreementKeyParser();

	/**
	 * Generates a hybrid key pair for digital signatures (Ed25519 + ML-DSA-65).
	 * <p>
	 * The resulting keys provide post-quantum security through the ML-DSA-65
	 * component while maintaining classical security through Ed25519. Both
	 * signatures must be forged to compromise authenticity.
	 *
	 * @return A KeyPair containing HybridSignaturePublicKey and HybridSignaturePrivateKey
	 */
	KeyPair generateHybridSignatureKeyPair();

	/**
	 * Returns a parser for hybrid signature keys.
	 *
	 * @return A KeyParser that can parse HybridSignaturePublicKey and HybridSignaturePrivateKey
	 */
	KeyParser getHybridSignatureKeyParser();

	/**
	 * Signs the given data with a hybrid private key (Ed25519 + ML-DSA-65).
	 * <p>
	 * Produces a concatenated signature where both Ed25519 and ML-DSA-65
	 * signatures must verify for the overall signature to be valid.
	 *
	 * @param label A namespaced label for domain separation
	 * @param toSign The data to sign
	 * @param privateKey The hybrid private key (must be HybridSignaturePrivateKey)
	 * @return The hybrid signature (3,373 bytes: Ed25519 64 + ML-DSA-65 3,309)
	 * @throws GeneralSecurityException If signing fails
	 */
	byte[] hybridSign(String label, byte[] toSign, PrivateKey privateKey)
			throws GeneralSecurityException;

	/**
	 * Verifies a hybrid signature.
	 * <p>
	 * Both the Ed25519 and ML-DSA-65 component signatures must be valid
	 * for the verification to succeed.
	 *
	 * @param signature The hybrid signature to verify
	 * @param label A namespaced label for domain separation
	 * @param signed The signed data
	 * @param publicKey The signer's hybrid public key (must be HybridSignaturePublicKey)
	 * @return true if both component signatures are valid
	 * @throws GeneralSecurityException If verification fails due to invalid keys
	 */
	boolean verifyHybridSignature(byte[] signature, String label, byte[] signed,
			PublicKey publicKey) throws GeneralSecurityException;

	/**
	 * Performs hybrid key encapsulation to a remote party's public key.
	 * <p>
	 * This is the initiator's operation in the hybrid key exchange. The returned
	 * encapsulation contains the KEM ciphertext to send and the partial shared
	 * secret to combine with ECDH.
	 *
	 * @param theirPublicKey The remote party's hybrid public key
	 * @return The encapsulation result (ciphertext + partial secret)
	 * @throws GeneralSecurityException If encapsulation fails
	 */
	HybridEncapsulationResult hybridEncapsulate(PublicKey theirPublicKey)
			throws GeneralSecurityException;

	/**
	 * Derives a hybrid shared secret as the initiator (who received KEM ciphertext).
	 *
	 * @param label A namespaced label for domain separation
	 * @param theirPublicKey The remote party's hybrid public key
	 * @param ourKeyPair Our hybrid key pair
	 * @param kemCiphertext The KEM ciphertext from the remote party
	 * @param inputs Additional inputs for key derivation
	 * @return The derived shared secret
	 * @throws GeneralSecurityException If key agreement fails
	 */
	SecretKey deriveHybridSharedSecret(String label, PublicKey theirPublicKey,
			KeyPair ourKeyPair, byte[] kemCiphertext, byte[]... inputs)
			throws GeneralSecurityException;

	/**
	 * Derives a hybrid shared secret as the responder (who generated KEM ciphertext).
	 *
	 * @param label A namespaced label for domain separation
	 * @param theirPublicKey The initiator's hybrid public key
	 * @param ourKeyPair Our hybrid key pair
	 * @param kemSecret The KEM shared secret from our encapsulation
	 * @param inputs Additional inputs for key derivation
	 * @return The derived shared secret
	 * @throws GeneralSecurityException If key agreement fails
	 */
	SecretKey deriveHybridSharedSecretAsResponder(String label,
			PublicKey theirPublicKey, KeyPair ourKeyPair, byte[] kemSecret,
			byte[]... inputs) throws GeneralSecurityException;

	/**
	 * Derives another secret key from the given secret key.
	 *
	 * @param label A namespaced label indicating the purpose of the derived
	 * key, to prevent it from being repurposed or colliding with a key derived
	 * for another purpose
	 * @param inputs Additional inputs that will be included in the derivation
	 * of the key
	 */
	SecretKey deriveKey(String label, SecretKey k, byte[]... inputs);

	/**
	 * Derives a shared secret from two key pairs.
	 *
	 * @param label A namespaced label indicating the purpose of this shared
	 * secret, to prevent it from being repurposed or colliding with a shared
	 * secret derived for another purpose
	 * @param theirPublicKey The public key of the remote party
	 * @param ourKeyPair The key pair of the local party
	 * @param inputs Additional inputs that will be included in the derivation
	 * of the shared secret
	 * @return The shared secret
	 */
	SecretKey deriveSharedSecret(String label, PublicKey theirPublicKey,
			KeyPair ourKeyPair, byte[]... inputs)
			throws GeneralSecurityException;

	/**
	 * Derives a shared secret from two static and two ephemeral key pairs.
	 * <p>
	 * Do not use this method for new protocols. The shared secret can be
	 * re-derived using the ephemeral public keys and both static private
	 * keys, so keys derived from the shared secret should not be used if
	 * forward secrecy is required. Use {@link #deriveSharedSecret(String,
	 * PublicKey, PublicKey, KeyPair, KeyPair, boolean, byte[]...)} instead.
	 * <p>
	 * TODO: Remove this after a reasonable migration period (added 2023-03-10).
	 * <p>
	 *
	 * @param label A namespaced label indicating the purpose of this shared
	 * secret, to prevent it from being repurposed or colliding with a shared
	 * secret derived for another purpose
	 * @param theirStaticPublicKey The static public key of the remote party
	 * @param theirEphemeralPublicKey The ephemeral public key of the remote
	 * party
	 * @param ourStaticKeyPair The static key pair of the local party
	 * @param ourEphemeralKeyPair The ephemeral key pair of the local party
	 * @param alice True if the local party is Alice
	 * @param inputs Additional inputs that will be included in the
	 * derivation of the shared secret
	 * @return The shared secret
	 */
	@Deprecated
	SecretKey deriveSharedSecretBadly(String label,
			PublicKey theirStaticPublicKey, PublicKey theirEphemeralPublicKey,
			KeyPair ourStaticKeyPair, KeyPair ourEphemeralKeyPair,
			boolean alice, byte[]... inputs)
			throws GeneralSecurityException;

	/**
	 * Derives a shared secret from two static and two ephemeral key pairs.
	 *
	 * @param label A namespaced label indicating the purpose of this shared
	 * secret, to prevent it from being repurposed or colliding with a shared
	 * secret derived for another purpose
	 * @param theirStaticPublicKey The static public key of the remote party
	 * @param theirEphemeralPublicKey The ephemeral public key of the remote
	 * party
	 * @param ourStaticKeyPair The static key pair of the local party
	 * @param ourEphemeralKeyPair The ephemeral key pair of the local party
	 * @param alice True if the local party is Alice
	 * @param inputs Additional inputs that will be included in the
	 * derivation of the shared secret
	 * @return The shared secret
	 */
	SecretKey deriveSharedSecret(String label, PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, boolean alice, byte[]... inputs)
			throws GeneralSecurityException;

	/**
	 * Signs the given byte[] with the given private key.
	 *
	 * @param label A namespaced label indicating the purpose of this
	 * signature, to prevent it from being repurposed or colliding with a
	 * signature created for another purpose
	 */
	byte[] sign(String label, byte[] toSign, PrivateKey privateKey)
			throws GeneralSecurityException;

	/**
	 * Verifies that the given signature is valid for the signed data
	 * and the given public key.
	 *
	 * @param label A namespaced label indicating the purpose of this
	 * signature, to prevent it from being repurposed or colliding with a
	 * signature created for another purpose
	 * @return True if the signature was valid, false otherwise.
	 */
	boolean verifySignature(byte[] signature, String label, byte[] signed,
			PublicKey publicKey) throws GeneralSecurityException;

	/**
	 * Returns the hash of the given inputs. The inputs are unambiguously
	 * combined by prefixing each input with its length.
	 *
	 * @param label A namespaced label indicating the purpose of this hash, to
	 * prevent it from being repurposed or colliding with a hash created for
	 * another purpose
	 */
	byte[] hash(String label, byte[]... inputs);

	/**
	 * Returns a message authentication code with the given key over the
	 * given inputs. The inputs are unambiguously combined by prefixing each
	 * input with its length.
	 *
	 * @param label A namespaced label indicating the purpose of this MAC, to
	 * prevent it from being repurposed or colliding with a MAC created for
	 * another purpose
	 */
	byte[] mac(String label, SecretKey macKey, byte[]... inputs);

	/**
	 * Verifies that the given message authentication code is valid for the
	 * given secret key and inputs.
	 *
	 * @param label A namespaced label indicating the purpose of this MAC, to
	 * prevent it from being repurposed or colliding with a MAC created for
	 * another purpose
	 * @return True if the MAC was valid, false otherwise.
	 */
	boolean verifyMac(byte[] mac, String label, SecretKey macKey,
			byte[]... inputs);

	/**
	 * Encrypts and authenticates the given plaintext so it can be written to
	 * storage. The encryption and authentication keys are derived from the
	 * given password. The ciphertext will be decryptable using the same
	 * password after the app restarts.
	 *
	 * @param keyStrengthener Used to strengthen the password-based key. If
	 * null, the password-based key will not be strengthened
	 */
	byte[] encryptWithPassword(byte[] plaintext, String password,
			@Nullable KeyStrengthener keyStrengthener);

	/**
	 * Decrypts and authenticates the given ciphertext that has been read from
	 * storage. The encryption and authentication keys are derived from the
	 * given password.
	 *
	 * @param keyStrengthener Used to strengthen the password-based key. If
	 * null, or if strengthening was not used when encrypting the ciphertext,
	 * the password-based key will not be strengthened
	 * @throws DecryptionException If the ciphertext cannot be decrypted and
	 * authenticated (for example, if the password is wrong).
	 */
	byte[] decryptWithPassword(byte[] ciphertext, String password,
			@Nullable KeyStrengthener keyStrengthener)
			throws DecryptionException;

	/**
	 * Returns true if the given ciphertext was encrypted using a strengthened
	 * key. The validity of the ciphertext is not checked.
	 */
	boolean isEncryptedWithStrengthenedKey(byte[] ciphertext);

	/**
	 * Encrypts the given plaintext to the given public key.
	 */
	byte[] encryptToKey(PublicKey publicKey, byte[] plaintext);

	/**
	 * Encodes the given data as a hex string divided into lines of the given
	 * length. The line terminator is CRLF.
	 */
	String asciiArmour(byte[] b, int lineLength);

	/**
	 * Encode the Onion given its public key. Specified here:
	 * https://gitweb.torproject.org/torspec.git/tree/rend-spec-v3.txt?id=29245fd5#n2135
	 *
	 * @return the encoded onion, base32 chars
	 */
	String encodeOnion(byte[] publicKey);

}
