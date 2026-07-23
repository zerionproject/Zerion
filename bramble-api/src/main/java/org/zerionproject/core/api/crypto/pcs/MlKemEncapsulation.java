package org.zerionproject.core.api.crypto.pcs;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_CIPHERTEXT_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_SHARED_SECRET_SIZE;

@Immutable
@NotNullByDefault
public class MlKemEncapsulation {

	private final byte[] ciphertext;
	private final byte[] sharedSecret;

	public MlKemEncapsulation(byte[] ciphertext, byte[] sharedSecret) {
		if (ciphertext.length != MLKEM_CIPHERTEXT_SIZE)
			throw new IllegalArgumentException();
		if (sharedSecret.length != MLKEM_SHARED_SECRET_SIZE)
			throw new IllegalArgumentException();
		this.ciphertext = ciphertext;
		this.sharedSecret = sharedSecret;
	}

	public byte[] getCiphertext() {
		return ciphertext;
	}

	public byte[] getSharedSecret() {
		return sharedSecret;
	}
}
