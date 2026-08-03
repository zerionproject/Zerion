package org.zerionproject.core.api.crypto;

import org.zerionproject.core.api.Bytes;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.CryptoConstants.KEY_TYPE_SIGNATURE;
import static org.zerionproject.core.api.crypto.CryptoConstants.MAX_SIGNATURE_PUBLIC_KEY_BYTES;

@Immutable
@NotNullByDefault
public class SignaturePublicKey extends Bytes implements PublicKey {

	public SignaturePublicKey(byte[] encoded) {
		super(encoded);
		if (encoded.length == 0 ||
				encoded.length > MAX_SIGNATURE_PUBLIC_KEY_BYTES) {
			throw new IllegalArgumentException();
		}
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
