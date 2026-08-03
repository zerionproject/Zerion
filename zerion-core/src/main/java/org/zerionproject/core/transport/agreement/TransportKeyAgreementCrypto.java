package org.zerionproject.core.transport.agreement;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

@NotNullByDefault
interface TransportKeyAgreementCrypto {

	KeyPair generateKeyPair();

	SecretKey deriveRootKey(KeyPair localKeyPair, PublicKey remotePublicKey)
			throws GeneralSecurityException;

	PublicKey parsePublicKey(byte[] encoded) throws FormatException;

	PrivateKey parsePrivateKey(byte[] encoded) throws FormatException;
}
