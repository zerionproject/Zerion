package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.HybridSignaturePrivateKey;
import org.briarproject.bramble.api.crypto.HybridSignaturePublicKey;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.SignaturePrivateKey;
import org.briarproject.bramble.api.crypto.SignaturePublicKey;
import org.briarproject.nullsafety.NotNullByDefault;

import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.annotation.concurrent.Immutable;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.ML_DSA_65_SIGNATURE_BYTES;


@NotNullByDefault
@Immutable
class HybridSignature {

	private static final int SIGNATURE_KEY_PAIR_BITS = 256;
	private static final EdDSAParameterSpec ED25519_SPEC =
			EdDSANamedCurveTable.getByName("Ed25519");

	private final SecureRandom secureRandom;
	private final KeyPairGenerator ed25519KeyPairGenerator;
	private final MlDsa65 mlDsa65;

	HybridSignature(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
		this.ed25519KeyPairGenerator = new KeyPairGenerator();
		this.ed25519KeyPairGenerator.initialize(SIGNATURE_KEY_PAIR_BITS, secureRandom);
		this.mlDsa65 = new MlDsa65(secureRandom);
	}

	
	KeyPair generateKeyPair() {
		java.security.KeyPair ed25519KeyPair = ed25519KeyPairGenerator.generateKeyPair();
		EdDSAPublicKey edPublicKey = (EdDSAPublicKey) ed25519KeyPair.getPublic();
		EdDSAPrivateKey edPrivateKey = (EdDSAPrivateKey) ed25519KeyPair.getPrivate();
		MlDsa65.MlDsaKeyPair mlDsaKeyPair = mlDsa65.generateKeyPair();
		HybridSignaturePublicKey publicKey = new HybridSignaturePublicKey(
				edPublicKey.getAbyte(),
				mlDsaKeyPair.getPublicKey()
		);

		HybridSignaturePrivateKey privateKey = new HybridSignaturePrivateKey(
				edPrivateKey.getSeed(),
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
			new EdDSAPublicKeySpec(publicKey.getEd25519PublicKey(), ED25519_SPEC);
		} catch (Exception e) {
			return false;
		}
		return mlDsa65.isValidPublicKey(publicKey.getMlDsaPublicKey());
	}
}
