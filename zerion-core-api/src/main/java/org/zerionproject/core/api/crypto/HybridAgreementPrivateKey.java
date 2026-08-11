package org.zerionproject.core.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PRIVATE_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_AGREEMENT;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_KEM_768_PRIVATE_KEY_BYTES;

@Immutable
@NotNullByDefault
public class HybridAgreementPrivateKey implements PrivateKey {

	private final byte[] encoded;

	public HybridAgreementPrivateKey(byte[] encoded) {
		if (encoded.length != HYBRID_AGREEMENT_PRIVATE_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid hybrid agreement private key length: " + encoded.length +
							", expected: " + HYBRID_AGREEMENT_PRIVATE_KEY_BYTES);
		}
		this.encoded = encoded;
	}

	public HybridAgreementPrivateKey(byte[] x25519PrivateKey, byte[] mlKemPrivateKey) {
		if (x25519PrivateKey.length != 32) {
			throw new IllegalArgumentException(
					"Invalid X25519 private key length: " + x25519PrivateKey.length);
		}
		if (mlKemPrivateKey.length != ML_KEM_768_PRIVATE_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid ML-KEM-768 private key length: " + mlKemPrivateKey.length);
		}

		encoded = new byte[HYBRID_AGREEMENT_PRIVATE_KEY_BYTES];
		System.arraycopy(x25519PrivateKey, 0, encoded, 0, 32);
		System.arraycopy(mlKemPrivateKey, 0, encoded, 32, mlKemPrivateKey.length);
	}

	@Override
	public String getKeyType() {
		return KEY_TYPE_HYBRID_AGREEMENT;
	}

	@Override
	public byte[] getEncoded() {
		return encoded;
	}

	public byte[] getX25519PrivateKey() {
		byte[] x25519 = new byte[32];
		System.arraycopy(encoded, 0, x25519, 0, 32);
		return x25519;
	}

	public byte[] getMlKemPrivateKey() {
		byte[] mlKem = new byte[ML_KEM_768_PRIVATE_KEY_BYTES];
		System.arraycopy(encoded, 32, mlKem, 0, ML_KEM_768_PRIVATE_KEY_BYTES);
		return mlKem;
	}

	public AgreementPrivateKey getX25519Component() {
		return new AgreementPrivateKey(getX25519PrivateKey());
	}

	public void clear() {
		Arrays.fill(encoded, (byte) 0);
	}
}
