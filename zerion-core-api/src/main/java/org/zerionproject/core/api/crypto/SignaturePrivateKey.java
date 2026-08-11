package org.zerionproject.core.api.crypto;

import org.zerionproject.core.api.Bytes;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.CryptoConstants.KEY_TYPE_SIGNATURE;

@Immutable
@NotNullByDefault
public class SignaturePrivateKey extends Bytes implements PrivateKey {

	public SignaturePrivateKey(byte[] bytes) {
		super(bytes);
	}

	@Override
	public String getKeyType() {
		return KEY_TYPE_SIGNATURE;
	}

	@Override
	public byte[] getEncoded() {
		return getBytes();
	}
}
