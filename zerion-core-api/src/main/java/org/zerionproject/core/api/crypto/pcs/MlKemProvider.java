package org.zerionproject.core.api.crypto.pcs;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface MlKemProvider {

	MlKemKeyPair generateKeyPair();

	MlKemEncapsulation encapsulate(byte[] encapsulationKey);

	/**
	 * Whether a peer-supplied encapsulation key is one the encapsulation
	 * accepts: the right length and every coefficient below the modulus.
	 * A key that only passes a length check makes the encapsulation throw
	 * later, on the sender's own thread.
	 */
	boolean isValidEncapsulationKey(byte[] encapsulationKey);

	byte[] decapsulate(byte[] decapsulationKey, byte[] ciphertext);

	byte[] hashEkSeedAndVector(byte[] ekSeed, byte[] ekVector);

	boolean verifyEkHash(byte[] ekSeed, byte[] ekVector, byte[] expectedHash);
}
