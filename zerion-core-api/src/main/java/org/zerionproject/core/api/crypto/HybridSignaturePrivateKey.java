package org.zerionproject.core.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PRIVATE_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_SIGNATURE;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_PRIVATE_KEY_BYTES;

@Immutable
@NotNullByDefault
public class HybridSignaturePrivateKey implements PrivateKey {

	private final byte[] encoded;

	public HybridSignaturePrivateKey(byte[] encoded) {
		if (encoded.length != HYBRID_SIGNATURE_PRIVATE_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid hybrid signature private key length: " + encoded.length +
							", expected: " + HYBRID_SIGNATURE_PRIVATE_KEY_BYTES);
		}
		this.encoded = encoded;
	}

	public HybridSignaturePrivateKey(byte[] ed25519PrivateKey, byte[] mlDsaPrivateKey) {
		if (ed25519PrivateKey.length != 32) {
			throw new IllegalArgumentException(
					"Invalid Ed25519 private key length: " + ed25519PrivateKey.length);
		}
		if (mlDsaPrivateKey.length != ML_DSA_65_PRIVATE_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid ML-DSA-65 private key length: " + mlDsaPrivateKey.length);
		}

		encoded = new byte[HYBRID_SIGNATURE_PRIVATE_KEY_BYTES];
		System.arraycopy(ed25519PrivateKey, 0, encoded, 0, 32);
		System.arraycopy(mlDsaPrivateKey, 0, encoded, 32, mlDsaPrivateKey.length);
	}

	@Override
	public String getKeyType() {
		return KEY_TYPE_HYBRID_SIGNATURE;
	}

	@Override
	public byte[] getEncoded() {
		return encoded;
	}

	public byte[] getEd25519PrivateKey() {
		byte[] ed25519 = new byte[32];
		System.arraycopy(encoded, 0, ed25519, 0, 32);
		return ed25519;
	}

	public byte[] getMlDsaPrivateKey() {
		byte[] mlDsa = new byte[ML_DSA_65_PRIVATE_KEY_BYTES];
		System.arraycopy(encoded, 32, mlDsa, 0, ML_DSA_65_PRIVATE_KEY_BYTES);
		return mlDsa;
	}

	public SignaturePrivateKey getEd25519Component() {
		return new SignaturePrivateKey(getEd25519PrivateKey());
	}

	public void clear() {
		Arrays.fill(encoded, (byte) 0);
	}
}
