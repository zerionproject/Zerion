package org.zerionproject.core.crypto;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.signers.Ed25519Signer;

import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import static org.zerionproject.core.api.crypto.CryptoConstants.KEY_TYPE_SIGNATURE;

@NotNullByDefault
class EdSignature implements Signature {

	private final Ed25519Signer signer = new Ed25519Signer();

	@Override
	public void initSign(PrivateKey k) throws GeneralSecurityException {
		if (!k.getKeyType().equals(KEY_TYPE_SIGNATURE))
			throw new IllegalArgumentException();
		signer.init(true, new Ed25519PrivateKeyParameters(k.getEncoded(), 0));
	}

	@Override
	public void initVerify(PublicKey k) throws GeneralSecurityException {
		if (!k.getKeyType().equals(KEY_TYPE_SIGNATURE))
			throw new IllegalArgumentException();
		signer.init(false, new Ed25519PublicKeyParameters(k.getEncoded(), 0));
	}

	@Override
	public void update(byte b) throws GeneralSecurityException {
		signer.update(b);
	}

	@Override
	public void update(byte[] b) throws GeneralSecurityException {
		signer.update(b, 0, b.length);
	}

	@Override
	public void update(byte[] b, int off, int len)
			throws GeneralSecurityException {
		signer.update(b, off, len);
	}

	@Override
	public byte[] sign() throws GeneralSecurityException {
		return signer.generateSignature();
	}

	@Override
	public boolean verify(byte[] sig) throws GeneralSecurityException {
		return signer.verifySignature(sig);
	}
}
