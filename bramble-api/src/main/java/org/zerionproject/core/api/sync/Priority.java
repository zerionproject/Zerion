package org.zerionproject.core.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class Priority {

	private final byte[] nonce;

	public Priority(byte[] nonce) {
		this.nonce = nonce;
	}

	public byte[] getNonce() {
		return nonce;
	}
}
