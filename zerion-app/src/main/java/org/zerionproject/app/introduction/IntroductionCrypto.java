package org.zerionproject.app.introduction;

import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.app.api.client.SessionId;

import java.security.GeneralSecurityException;

import javax.annotation.Nullable;

interface IntroductionCrypto {

	SessionId getSessionId(Author introducer, Author local, Author remote);

	boolean isAlice(AuthorId local, AuthorId remote);

	KeyPair generateAgreementKeyPair();

	/**
	 * Generate a fresh per-introduction ML-KEM-768
	 * ephemeral keypair. Returns {@code [privateKey, publicKey]} as raw
	 * byte arrays. Private key is 2400 B; public key is 1184 B.
	 */
	byte[][] generateMlKemEphemeralKeyPair();

	/**
	 * Encapsulate a per-introduction ML-KEM-768 shared
	 * secret to the peer's ephemeral ML-KEM public key. Returns
	 * {@code [ciphertext, sharedSecret]} where ciphertext is 1088 B and
	 * sharedSecret is 32 B.
	 */
	byte[][] encapsulateMlKem(byte[] peerMlKemPub);

	/**
	 * Decapsulate a per-introduction ML-KEM-768 shared
	 * secret. Returns the 32 B shared secret.
	 */
	byte[] decapsulateMlKem(byte[] localMlKemPriv, byte[] ciphertext);

	SecretKey deriveMasterKey(IntroduceeSession s)
			throws GeneralSecurityException;

	/**
	 * Derive the pre-master key used for AUTH MAC keys
	 * during hybrid KEM introductions. Combines the X25519 DH output
	 * (via the existing deriveMasterKey path) with a single
	 * per-introduction ML-KEM-768 shared secret.
	 * <p>
	 * For producing the local AUTH MAC, pass our own encapsulation's
	 * shared secret. For verifying the peer's AUTH MAC, pass the shared
	 * secret recovered by decapsulating the peer's ciphertext.
	 */
	SecretKey derivePreMasterKey(IntroduceeSession s, byte[] kemSecret)
			throws GeneralSecurityException;

	/**
	 * Derive the final symmetric master key after both
	 * AUTHs have been exchanged. Combines the X25519 DH output with both
	 * ML-KEM-768 shared secrets (own encap output and peer's encap
	 * output via decap). Both sides arrive at the same value.
	 */
	SecretKey deriveFinalMasterKey(IntroduceeSession s, byte[] aliceKemSecret,
			byte[] bobKemSecret) throws GeneralSecurityException;

	SecretKey deriveMacKey(SecretKey masterKey, boolean alice);

	byte[] authMac(SecretKey macKey, IntroduceeSession s,
			AuthorId localAuthorId);

	void verifyAuthMac(byte[] mac, IntroduceeSession s, AuthorId localAuthorId)
			throws GeneralSecurityException;

	/**
	 * Verify peer's AUTH MAC using an explicit MAC key
	 * (derived from peer's pre-master in hybrid KEM introductions). The
	 * session's stored remote MAC key is ignored.
	 */
	void verifyAuthMacWithKey(byte[] mac, IntroduceeSession s,
			AuthorId localAuthorId, SecretKey peerMacKey)
			throws GeneralSecurityException;

	byte[] sign(SecretKey macKey, PrivateKey privateKey,
			@Nullable byte[] localMlDsaPriv,
			@Nullable byte[] remoteMlDsaPub)
			throws GeneralSecurityException;

	void verifySignature(byte[] signature, IntroduceeSession s)
			throws GeneralSecurityException;

	/**
	 * Verify peer's hybrid signature using an explicit
	 * MAC key for the signature nonce derivation. Used during hybrid KEM
	 * AUTH-receive when the session's stored remote MAC key was derived
	 * from our pre-master, not peer's.
	 */
	void verifySignatureWithKey(byte[] signature, IntroduceeSession s,
			SecretKey peerMacKey) throws GeneralSecurityException;

	byte[] activateMac(IntroduceeSession s);

	void verifyActivateMac(byte[] mac, IntroduceeSession s)
			throws GeneralSecurityException;

}
