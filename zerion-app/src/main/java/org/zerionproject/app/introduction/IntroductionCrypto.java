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

	byte[][] generateMlKemEphemeralKeyPair();

	byte[][] encapsulateMlKem(byte[] peerMlKemPub)
			throws GeneralSecurityException;

	boolean isValidMlKemPublicKey(byte[] peerMlKemPub);

	byte[] decapsulateMlKem(byte[] localMlKemPriv, byte[] ciphertext);

	SecretKey deriveMasterKey(IntroduceeSession s)
			throws GeneralSecurityException;

	SecretKey derivePreMasterKey(IntroduceeSession s, byte[] kemSecret)
			throws GeneralSecurityException;

	SecretKey deriveFinalMasterKey(IntroduceeSession s, byte[] aliceKemSecret,
			byte[] bobKemSecret) throws GeneralSecurityException;

	SecretKey deriveMacKey(SecretKey masterKey, boolean alice);

	SecretKey deriveActivateKey(SecretKey finalMasterKey, boolean alice);

	byte[] authMac(SecretKey macKey, IntroduceeSession s,
			AuthorId localAuthorId);

	void verifyAuthMac(byte[] mac, IntroduceeSession s, AuthorId localAuthorId)
			throws GeneralSecurityException;

	void verifyAuthMacWithKey(byte[] mac, IntroduceeSession s,
			AuthorId localAuthorId, SecretKey peerMacKey)
			throws GeneralSecurityException;

	byte[] sign(SecretKey macKey, PrivateKey privateKey,
			@Nullable byte[] localMlDsaPriv,
			@Nullable byte[] remoteMlDsaPub)
			throws GeneralSecurityException;

	void verifySignature(byte[] signature, IntroduceeSession s)
			throws GeneralSecurityException;

	void verifySignatureWithKey(byte[] signature, IntroduceeSession s,
			SecretKey peerMacKey) throws GeneralSecurityException;

	byte[] activateMac(IntroduceeSession s);

	void verifyActivateMac(byte[] mac, IntroduceeSession s)
			throws GeneralSecurityException;

}
