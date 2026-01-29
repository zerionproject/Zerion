package org.briarproject.briar.introduction;

import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.LocalAuthor;
import org.briarproject.briar.api.client.SessionId;

import java.security.GeneralSecurityException;

interface IntroductionCrypto {

	
	SessionId getSessionId(Author introducer, Author local, Author remote);

	
	boolean isAlice(AuthorId local, AuthorId remote);

	
	KeyPair generateAgreementKeyPair();

	
	SecretKey deriveMasterKey(IntroduceeSession s)
			throws GeneralSecurityException;

	
	SecretKey deriveMacKey(SecretKey masterKey, boolean alice);

	
	byte[] authMac(SecretKey macKey, IntroduceeSession s,
			AuthorId localAuthorId);

	
	void verifyAuthMac(byte[] mac, IntroduceeSession s, AuthorId localAuthorId)
			throws GeneralSecurityException;

	
	byte[] sign(SecretKey macKey, PrivateKey privateKey)
			throws GeneralSecurityException;

	
	void verifySignature(byte[] signature, IntroduceeSession s)
			throws GeneralSecurityException;

	
	byte[] activateMac(IntroduceeSession s);

	
	void verifyActivateMac(byte[] mac, IntroduceeSession s)
			throws GeneralSecurityException;

}
