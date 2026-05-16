package org.briarproject.briar.introduction;

import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.briar.api.client.SessionId;

import java.security.GeneralSecurityException;

import javax.annotation.Nullable;

interface IntroductionCrypto {

	SessionId getSessionId(Author introducer, Author local, Author remote);

	boolean isAlice(AuthorId local, AuthorId remote);

	KeyPair generateAgreementKeyPair();

	/**
	 * v1.7 Phase 5b — generate a fresh per-introduction ML-KEM-768
	 * ephemeral keypair. Returns {@code [privateKey, publicKey]} as raw
	 * byte arrays. Private key is 2400 B; public key is 1184 B.
	 */
	byte[][] generateMlKemEphemeralKeyPair();

	/**
	 * v1.7 Phase 5b — encapsulate a per-introduction ML-KEM-768 shared
	 * secret to the peer's ephemeral ML-KEM public key. Returns
	 * {@code [ciphertext, sharedSecret]} where ciphertext is 1088 B and
	 * sharedSecret is 32 B.
	 */
	byte[][] encapsulateMlKem(byte[] peerMlKemPub);

	/**
	 * v1.7 Phase 5b — decapsulate a per-introduction ML-KEM-768 shared
	 * secret. Returns the 32 B shared secret.
	 */
	byte[] decapsulateMlKem(byte[] localMlKemPriv, byte[] ciphertext);

	SecretKey deriveMasterKey(IntroduceeSession s)
			throws GeneralSecurityException;

	/**
	 * v1.7 Phase 5b — derive the pre-master key used for AUTH MAC keys
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
	 * v1.7 Phase 5b — derive the final symmetric master key after both
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

	byte[] sign(SecretKey macKey, PrivateKey privateKey,
			@Nullable byte[] localMlDsaPriv,
			@Nullable byte[] remoteMlDsaPub)
			throws GeneralSecurityException;

	void verifySignature(byte[] signature, IntroduceeSession s)
			throws GeneralSecurityException;

	byte[] activateMac(IntroduceeSession s);

	void verifyActivateMac(byte[] mac, IntroduceeSession s)
			throws GeneralSecurityException;

}
