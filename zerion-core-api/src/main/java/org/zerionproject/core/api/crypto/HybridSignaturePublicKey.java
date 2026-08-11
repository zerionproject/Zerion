package org.zerionproject.core.api.crypto;

import org.zerionproject.core.api.Bytes;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_SIGNATURE;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES;

@Immutable
@NotNullByDefault
public class HybridSignaturePublicKey extends Bytes implements PublicKey {

	public HybridSignaturePublicKey(byte[] encoded) {
		super(encoded);
		if (encoded.length != HYBRID_SIGNATURE_PUBLIC_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid hybrid signature public key length: " + encoded.length +
							", expected: " + HYBRID_SIGNATURE_PUBLIC_KEY_BYTES);
		}
	}

	public HybridSignaturePublicKey(byte[] ed25519PublicKey, byte[] mlDsaPublicKey) {
		super(combineKeys(ed25519PublicKey, mlDsaPublicKey));
	}

	private static byte[] combineKeys(byte[] ed25519PublicKey, byte[] mlDsaPublicKey) {
		if (ed25519PublicKey.length != 32) {
			throw new IllegalArgumentException(
					"Invalid Ed25519 public key length: " + ed25519PublicKey.length);
		}
		if (mlDsaPublicKey.length != ML_DSA_65_PUBLIC_KEY_BYTES) {
			throw new IllegalArgumentException(
					"Invalid ML-DSA-65 public key length: " + mlDsaPublicKey.length);
		}

		byte[] encoded = new byte[HYBRID_SIGNATURE_PUBLIC_KEY_BYTES];
		System.arraycopy(ed25519PublicKey, 0, encoded, 0, 32);
		System.arraycopy(mlDsaPublicKey, 0, encoded, 32, mlDsaPublicKey.length);
		return encoded;
	}

	@Override
	public String getKeyType() {
		return KEY_TYPE_HYBRID_SIGNATURE;
	}

	@Override
	public byte[] getEncoded() {
		return getBytes();
	}

	public byte[] getEd25519PublicKey() {
		byte[] ed25519 = new byte[32];
		System.arraycopy(getBytes(), 0, ed25519, 0, 32);
		return ed25519;
	}

	public byte[] getMlDsaPublicKey() {
		byte[] mlDsa = new byte[ML_DSA_65_PUBLIC_KEY_BYTES];
		System.arraycopy(getBytes(), 32, mlDsa, 0, ML_DSA_65_PUBLIC_KEY_BYTES);
		return mlDsa;
	}

	public SignaturePublicKey getEd25519Component() {
		return new SignaturePublicKey(getEd25519PublicKey());
	}
}
