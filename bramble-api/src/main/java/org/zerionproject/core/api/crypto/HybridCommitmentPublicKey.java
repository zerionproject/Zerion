package org.zerionproject.core.api.crypto;

import org.zerionproject.core.api.Bytes;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class HybridCommitmentPublicKey extends Bytes implements PublicKey {

	public static final String KEY_TYPE_HYBRID_COMMITMENT = "Hybrid-Commitment";

	private static final int MAX_HYBRID_COMMITMENT_BYTES = 128;

	public HybridCommitmentPublicKey(byte[] encoded) {
		super(encoded);
		if (encoded.length == 0 ||
				encoded.length > MAX_HYBRID_COMMITMENT_BYTES) {
			throw new IllegalArgumentException();
		}
	}

	@Override
	public String getKeyType() {
		return KEY_TYPE_HYBRID_COMMITMENT;
	}

	@Override
	public byte[] getEncoded() {
		return getBytes();
	}
}
