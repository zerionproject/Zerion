package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.annotation.concurrent.Immutable;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_SIGNATURE_BYTES;

@NotNullByDefault
@Immutable
class HybridSignature {

	private final SecureRandom secureRandom;
	private final MlDsa65 mlDsa65;

	HybridSignature(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
		this.mlDsa65 = new MlDsa65(secureRandom);
	}

	KeyPair generateKeyPair() {
		Ed25519PrivateKeyParameters edPrivateKey =
				new Ed25519PrivateKeyParameters(secureRandom);
		Ed25519PublicKeyParameters edPublicKey =
				edPrivateKey.generatePublicKey();
		MlDsa65.MlDsaKeyPair mlDsaKeyPair = mlDsa65.generateKeyPair();
		HybridSignaturePublicKey publicKey = new HybridSignaturePublicKey(
				edPublicKey.getEncoded(),
				mlDsaKeyPair.getPublicKey()
		);

		HybridSignaturePrivateKey privateKey = new HybridSignaturePrivateKey(
				edPrivateKey.getEncoded(),
				mlDsaKeyPair.getPrivateKey()
		);

		return new KeyPair(publicKey, privateKey);
	}

	byte[] sign(byte[] message, HybridSignaturePrivateKey privateKey)
			throws GeneralSecurityException {
		byte[] ed25519Signature = signEd25519(message, privateKey.getEd25519PrivateKey());
		byte[] mlDsaSignature = mlDsa65.sign(privateKey.getMlDsaPrivateKey(), message);
		byte[] hybridSignature = new byte[HYBRID_SIGNATURE_BYTES];
		System.arraycopy(ed25519Signature, 0, hybridSignature, 0, 64);
		System.arraycopy(mlDsaSignature, 0, hybridSignature, 64, mlDsaSignature.length);

		return hybridSignature;
	}

	boolean verify(byte[] signature, byte[] message, HybridSignaturePublicKey publicKey)
			throws GeneralSecurityException {
		if (signature.length != HYBRID_SIGNATURE_BYTES) {
			return false;
		}
		byte[] ed25519Signature = new byte[64];
		byte[] mlDsaSignature = new byte[ML_DSA_65_SIGNATURE_BYTES];
		System.arraycopy(signature, 0, ed25519Signature, 0, 64);
		System.arraycopy(signature, 64, mlDsaSignature, 0, ML_DSA_65_SIGNATURE_BYTES);
		boolean ed25519Valid = verifyEd25519(ed25519Signature, message,
				publicKey.getEd25519PublicKey());
		boolean mlDsaValid = mlDsa65.verify(publicKey.getMlDsaPublicKey(),
				message, mlDsaSignature);
		return ed25519Valid && mlDsaValid;
	}

	private byte[] signEd25519(byte[] message, byte[] privateKeySeed)
			throws GeneralSecurityException {
		EdSignature signature = new EdSignature();
		signature.initSign(new SignaturePrivateKey(privateKeySeed));
		signature.update(message);
		return signature.sign();
	}

	private boolean verifyEd25519(byte[] signature, byte[] message, byte[] publicKeyBytes)
			throws GeneralSecurityException {
		try {
			EdSignature verifier = new EdSignature();
			verifier.initVerify(new SignaturePublicKey(publicKeyBytes));
			verifier.update(message);
			return verifier.verify(signature);
		} catch (Exception e) {
			return false;
		}
	}

	boolean isValidPublicKey(HybridSignaturePublicKey publicKey) {
		try {
			new Ed25519PublicKeyParameters(publicKey.getEd25519PublicKey(), 0);
		} catch (Exception e) {
			return false;
		}
		return mlDsa65.isValidPublicKey(publicKey.getMlDsaPublicKey());
	}
}
