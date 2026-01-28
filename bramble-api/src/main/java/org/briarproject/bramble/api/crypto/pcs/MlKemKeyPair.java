package org.briarproject.bramble.api.crypto.pcs;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_CIPHERTEXT_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_DECAPSULATION_KEY_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_EK_SEED_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_EK_VECTOR_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_ENCAPSULATION_KEY_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_SHARED_SECRET_SIZE;

@Immutable
@NotNullByDefault
public class MlKemKeyPair {

	private final byte[] encapsulationKey;
	private final byte[] decapsulationKey;
	private final byte[] ekSeed;
	private final byte[] ekVector;

	public MlKemKeyPair(byte[] encapsulationKey, byte[] decapsulationKey,
			byte[] ekSeed, byte[] ekVector) {
		if (encapsulationKey.length != MLKEM_ENCAPSULATION_KEY_SIZE)
			throw new IllegalArgumentException();
		if (decapsulationKey.length != MLKEM_DECAPSULATION_KEY_SIZE)
			throw new IllegalArgumentException();
		if (ekSeed.length != MLKEM_EK_SEED_SIZE)
			throw new IllegalArgumentException();
		if (ekVector.length != MLKEM_EK_VECTOR_SIZE)
			throw new IllegalArgumentException();
		this.encapsulationKey = encapsulationKey;
		this.decapsulationKey = decapsulationKey;
		this.ekSeed = ekSeed;
		this.ekVector = ekVector;
	}

	public byte[] getEncapsulationKey() {
		return encapsulationKey;
	}

	public byte[] getDecapsulationKey() {
		return decapsulationKey;
	}

	public byte[] getEkSeed() {
		return ekSeed;
	}

	public byte[] getEkVector() {
		return ekVector;
	}

	/**
	 * Reconstructs MlKemKeyPair from stored components.
	 * The encapsulation key is reconstructed from ekSeed + ekVector.
	 *
	 * @param ekSeed The encapsulation key seed (32 bytes)
	 * @param ekVector The encapsulation key vector (1152 bytes)
	 * @param decapsulationKey The decapsulation key (2400 bytes)
	 * @return A new MlKemKeyPair instance
	 */
	public static MlKemKeyPair fromComponents(byte[] ekSeed, byte[] ekVector,
			byte[] decapsulationKey) {
		// Reconstruct encapsulation key from ekSeed + ekVector
		byte[] encapsulationKey = new byte[MLKEM_ENCAPSULATION_KEY_SIZE];
		System.arraycopy(ekSeed, 0, encapsulationKey, 0, ekSeed.length);
		System.arraycopy(ekVector, 0, encapsulationKey, ekSeed.length, ekVector.length);
		return new MlKemKeyPair(encapsulationKey, decapsulationKey, ekSeed, ekVector);
	}
}
