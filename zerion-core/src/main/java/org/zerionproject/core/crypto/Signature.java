package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

@NotNullByDefault
interface Signature {

	void initSign(PrivateKey k) throws GeneralSecurityException;

	void initVerify(PublicKey k) throws GeneralSecurityException;

	void update(byte b) throws GeneralSecurityException;

	void update(byte[] b) throws GeneralSecurityException;

	void update(byte[] b, int off, int len) throws GeneralSecurityException;

	byte[] sign() throws GeneralSecurityException;

	boolean verify(byte[] signature) throws GeneralSecurityException;
}
