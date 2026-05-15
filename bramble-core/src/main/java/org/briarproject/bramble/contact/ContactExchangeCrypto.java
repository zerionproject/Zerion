package org.briarproject.bramble.contact;

import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
interface ContactExchangeCrypto {

	SecretKey deriveHeaderKey(SecretKey masterKey, boolean alice);

	byte[] sign(PrivateKey privateKey, SecretKey masterKey, boolean alice);

	boolean verify(PublicKey publicKey, SecretKey masterKey, boolean alice,
			byte[] signature);
}
