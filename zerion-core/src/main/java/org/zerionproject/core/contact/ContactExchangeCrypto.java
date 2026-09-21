package org.zerionproject.core.contact;

import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
interface ContactExchangeCrypto {

	SecretKey deriveHeaderKey(SecretKey masterKey, boolean alice);

	byte[] sign(PrivateKey privateKey, SecretKey masterKey, boolean alice);

	boolean verify(PublicKey publicKey, SecretKey masterKey, boolean alice,
			byte[] signature);

	/**
	 * Signs the nonce bound to this exchange with the local hybrid identity
	 * (Ed25519 and ML-DSA-65 over the same input), so that the peer can
	 * verify possession of both halves of the identity it is about to store.
	 */
	byte[] hybridSign(PrivateKey ed25519PrivateKey, byte[] mlDsaPrivateKey,
			SecretKey masterKey, boolean alice);

	boolean verifyHybrid(PublicKey ed25519PublicKey, byte[] mlDsaPublicKey,
			SecretKey masterKey, boolean alice, byte[] signature);
}
