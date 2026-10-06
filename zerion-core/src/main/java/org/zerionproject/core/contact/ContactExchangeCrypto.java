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

	byte[] hybridSign(PrivateKey ed25519PrivateKey, byte[] mlDsaPrivateKey,
			SecretKey masterKey, boolean alice);

	boolean verifyHybrid(PublicKey ed25519PublicKey, byte[] mlDsaPublicKey,
			SecretKey masterKey, boolean alice, byte[] signature);
}
