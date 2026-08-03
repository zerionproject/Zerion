package org.zerionproject.core.api.crypto;

import org.zerionproject.core.api.Bytes;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_AGREEMENT;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_KEM_768_PUBLIC_KEY_BYTES;

@Immutable
@NotNullByDefault
public class HybridAgreementPublicKey extends Bytes implements PublicKey {

	public HybridAgreementPublicKey(byte[] encoded) {
		super(encoded);
		if (encoded.length != HYBRID_AGREEMENT_PUBLIC_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid hybrid agreement public key length: " + encoded.length +
							", expected: " + HYBRID_AGREEMENT_PUBLIC_KEY_BYTES);
		}
	}

	public HybridAgreementPublicKey(byte[] x25519PublicKey, byte[] mlKemPublicKey) {
		super(combineKeys(x25519PublicKey, mlKemPublicKey));
	}

	private static byte[] combineKeys(byte[] x25519PublicKey, byte[] mlKemPublicKey) {
		if (x25519PublicKey.length != 32) {
			throw new IllegalArgumentException(
					"Invalid X25519 public key length: " + x25519PublicKey.length);
		}
		if (mlKemPublicKey.length != ML_KEM_768_PUBLIC_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid ML-KEM-768 public key length: " + mlKemPublicKey.length);
		}

		byte[] encoded = new byte[HYBRID_AGREEMENT_PUBLIC_KEY_BYTES];
		System.arraycopy(x25519PublicKey, 0, encoded, 0, 32);
		System.arraycopy(mlKemPublicKey, 0, encoded, 32, mlKemPublicKey.length);
		return encoded;
	}

	@Override
	public String getKeyType() {
		return KEY_TYPE_HYBRID_AGREEMENT;
	}

	@Override
	public byte[] getEncoded() {
		return getBytes();
	}

	public byte[] getX25519PublicKey() {
		byte[] x25519 = new byte[32];
		System.arraycopy(getBytes(), 0, x25519, 0, 32);
		return x25519;
	}

	public byte[] getMlKemPublicKey() {
		byte[] mlKem = new byte[ML_KEM_768_PUBLIC_KEY_BYTES];
		System.arraycopy(getBytes(), 32, mlKem, 0, ML_KEM_768_PUBLIC_KEY_BYTES);
		return mlKem;
	}

	public AgreementPublicKey getX25519Component() {
		return new AgreementPublicKey(getX25519PublicKey());
	}
}
