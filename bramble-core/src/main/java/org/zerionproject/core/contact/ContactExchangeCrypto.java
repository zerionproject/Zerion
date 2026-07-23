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
}
