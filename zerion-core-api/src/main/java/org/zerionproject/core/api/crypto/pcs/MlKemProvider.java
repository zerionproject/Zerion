package org.zerionproject.core.api.crypto.pcs;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface MlKemProvider {

	MlKemKeyPair generateKeyPair();

	MlKemEncapsulation encapsulate(byte[] encapsulationKey);

	byte[] decapsulate(byte[] decapsulationKey, byte[] ciphertext);

	byte[] hashEkSeedAndVector(byte[] ekSeed, byte[] ekVector);

	boolean verifyEkHash(byte[] ekSeed, byte[] ekVector, byte[] expectedHash);
}
