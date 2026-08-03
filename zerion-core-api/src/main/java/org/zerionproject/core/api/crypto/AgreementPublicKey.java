package org.zerionproject.core.api.crypto;

import org.zerionproject.core.api.Bytes;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.CryptoConstants.KEY_TYPE_AGREEMENT;
import static org.zerionproject.core.api.crypto.CryptoConstants.MAX_AGREEMENT_PUBLIC_KEY_BYTES;

@Immutable
@NotNullByDefault
public class AgreementPublicKey extends Bytes implements PublicKey {

	public AgreementPublicKey(byte[] encoded) {
		super(encoded);
		if (encoded.length == 0 ||
				encoded.length > MAX_AGREEMENT_PUBLIC_KEY_BYTES) {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public String getKeyType() {
		return KEY_TYPE_AGREEMENT;
	}

	@Override
	public byte[] getEncoded() {
		return getBytes();
	}
}
