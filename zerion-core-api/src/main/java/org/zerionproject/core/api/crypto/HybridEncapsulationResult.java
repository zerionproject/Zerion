package org.zerionproject.core.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

@NotNullByDefault
public class HybridEncapsulationResult {

	private final byte[] ciphertext;
	private final byte[] sharedSecret;

	public HybridEncapsulationResult(byte[] ciphertext, byte[] sharedSecret) {
		this.ciphertext = ciphertext;
		this.sharedSecret = sharedSecret;
	}

	public byte[] getCiphertext() {
		return ciphertext;
	}

	public byte[] getSharedSecret() {
		return sharedSecret;
	}

	public void clearSecret() {
		Arrays.fill(sharedSecret, (byte) 0);
	}
}
