package org.briarproject.bramble.contact;

import org.briarproject.bramble.api.crypto.HybridEncapsulationResult;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

@NotNullByDefault
interface HandshakeCrypto {

	KeyPair generateEphemeralKeyPair();

	/**
	 * Generates a hybrid ephemeral key pair for PQ handshakes.
	 */
	KeyPair generateHybridEphemeralKeyPair();

	/**
	 * Derives the master key from the given static and ephemeral keys using
	 * the deprecated v0.0 key derivation method.
	 * <p>
	 * TODO: Remove this after a reasonable migration period (added 2023-03-10).
	 *
	 * @param alice Whether the local peer is Alice
	 */
	@Deprecated
	SecretKey deriveMasterKey_0_0(PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, boolean alice)
			throws GeneralSecurityException;

	/**
	 * Derives the master key from the given static and ephemeral keys using
	 * the v0.1 key derivation method.
	 *
	 * @param alice Whether the local peer is Alice
	 */
	SecretKey deriveMasterKey_0_1(PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, boolean alice)
			throws GeneralSecurityException;

	/**
	 * Performs hybrid KEM encapsulation to the remote party's public key.
	 * Used by the initiator (Alice) in hybrid PQ handshakes.
	 *
	 * @param theirPublicKey The remote party's hybrid public key
	 * @return The encapsulation result containing ciphertext and partial secret
	 */
	HybridEncapsulationResult hybridEncapsulate(PublicKey theirPublicKey)
			throws GeneralSecurityException;

	/**
	 * Derives the master key using hybrid post-quantum key agreement.
	 * Used for Zerion-to-Zerion PQ-secure handshakes.
	 *
	 * @param theirStaticPublicKey The remote party's hybrid static public key
	 * @param theirEphemeralPublicKey The remote party's hybrid ephemeral public key
	 * @param ourStaticKeyPair Our hybrid static key pair
	 * @param ourEphemeralKeyPair Our hybrid ephemeral key pair
	 * @param kemCiphertext The KEM ciphertext (for responder who decapsulates)
	 * @param kemSecret The KEM secret (for initiator who encapsulated)
	 * @param alice Whether the local peer is Alice (initiator)
	 */
	SecretKey deriveHybridMasterKey(PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, byte[] kemCiphertext,
			byte[] kemSecret, boolean alice)
			throws GeneralSecurityException;

	/**
	 * Returns proof that the local peer knows the master key and therefore
	 * owns the static and ephemeral public keys sent by the local peer.
	 *
	 * @param alice Whether the proof is being created by Alice
	 */
	byte[] proveOwnership(SecretKey masterKey, boolean alice);

	/**
	 * Verifies the given proof that the remote peer knows the master key and
	 * therefore owns the static and ephemeral keys sent by the remote peer.
	 *
	 * @param alice Whether the proof was created by Alice
	 */
	boolean verifyOwnership(SecretKey masterKey, boolean alice, byte[] proof);
}
