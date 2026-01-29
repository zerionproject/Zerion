package org.briarproject.bramble.crypto;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.briarproject.bramble.api.crypto.HybridAgreementPrivateKey;
import org.briarproject.bramble.api.crypto.HybridAgreementPublicKey;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.util.ByteUtils;
import org.briarproject.bramble.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;
import org.whispersystems.curve25519.Curve25519;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.annotation.concurrent.Immutable;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SHARED_SECRET_LABEL;
import static org.briarproject.bramble.util.ByteUtils.INT_32_BYTES;


@NotNullByDefault
@Immutable
class HybridKeyAgreement {

	private final SecureRandom secureRandom;
	private final Curve25519 curve25519;
	private final MlKem768 mlKem768;

	HybridKeyAgreement(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
		this.curve25519 = Curve25519.getInstance("java");
		this.mlKem768 = new MlKem768(secureRandom);
	}

	
	KeyPair generateKeyPair() {
		org.whispersystems.curve25519.Curve25519KeyPair x25519KeyPair =
				curve25519.generateKeyPair();
		MlKem768.MlKemKeyPair mlKemKeyPair = mlKem768.generateKeyPair();
		HybridAgreementPublicKey publicKey = new HybridAgreementPublicKey(
				x25519KeyPair.getPublicKey(),
				mlKemKeyPair.getPublicKey()
		);

		HybridAgreementPrivateKey privateKey = new HybridAgreementPrivateKey(
				x25519KeyPair.getPrivateKey(),
				mlKemKeyPair.getPrivateKey()
		);

		return new KeyPair(publicKey, privateKey);
	}

	
	SecretKey deriveSharedSecret(String label,
			HybridAgreementPublicKey theirPublicKey,
			KeyPair ourKeyPair,
			byte[] kemCiphertext,
			byte[]... inputs) throws GeneralSecurityException {

		HybridAgreementPrivateKey ourPrivateKey =
				(HybridAgreementPrivateKey) ourKeyPair.getPrivate();
		byte[] x25519Secret = curve25519.calculateAgreement(
				theirPublicKey.getX25519PublicKey(),
				ourPrivateKey.getX25519PrivateKey()
		);
		if (isAllZeros(x25519Secret)) {
			throw new GeneralSecurityException(
					"Invalid X25519 shared secret (all zeros - possible low-order point attack)");
		}
		byte[] kemSecret = mlKem768.decapsulate(
				ourPrivateKey.getMlKemPrivateKey(),
				kemCiphertext
		);
		SecretKey sharedSecret = combineSecrets(label, x25519Secret, kemSecret,
				theirPublicKey.getEncoded(),
				ourKeyPair.getPublic().getEncoded(),
				inputs);
		Arrays.fill(x25519Secret, (byte) 0);
		Arrays.fill(kemSecret, (byte) 0);

		return sharedSecret;
	}

	
	HybridEncapsulation encapsulate(HybridAgreementPublicKey theirPublicKey)
			throws GeneralSecurityException {
		MlKem768.MlKemEncapsulation enc = mlKem768.encapsulate(
				theirPublicKey.getMlKemPublicKey()
		);
		return new HybridEncapsulation(enc.getCiphertext(), enc.getSharedSecret());
	}

	
	SecretKey deriveSharedSecretAsResponder(String label,
			HybridAgreementPublicKey theirPublicKey,
			KeyPair ourKeyPair,
			byte[] kemSecret,
			byte[]... inputs) throws GeneralSecurityException {

		HybridAgreementPrivateKey ourPrivateKey =
				(HybridAgreementPrivateKey) ourKeyPair.getPrivate();
		byte[] x25519Secret = curve25519.calculateAgreement(
				theirPublicKey.getX25519PublicKey(),
				ourPrivateKey.getX25519PrivateKey()
		);
		if (isAllZeros(x25519Secret)) {
			throw new GeneralSecurityException(
					"Invalid X25519 shared secret (all zeros)");
		}
		SecretKey sharedSecret = combineSecrets(label, x25519Secret, kemSecret,
				theirPublicKey.getEncoded(),
				ourKeyPair.getPublic().getEncoded(),
				inputs);
		Arrays.fill(x25519Secret, (byte) 0);

		return sharedSecret;
	}

	
	private SecretKey combineSecrets(String label,
			byte[] x25519Secret,
			byte[] kemSecret,
			byte[] theirPublicKey,
			byte[] ourPublicKey,
			byte[]... additionalInputs) {
		Blake2bDigest digest = new Blake2bDigest(256);
		byte[] labelBytes = StringUtils.toUtf8(HYBRID_SHARED_SECRET_LABEL + "/" + label);
		byte[] length = new byte[INT_32_BYTES];
		ByteUtils.writeUint32(labelBytes.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(labelBytes, 0, labelBytes.length);
		ByteUtils.writeUint32(x25519Secret.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(x25519Secret, 0, x25519Secret.length);
		ByteUtils.writeUint32(kemSecret.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(kemSecret, 0, kemSecret.length);
		byte[] firstKey, secondKey;
		if (compareBytes(ourPublicKey, theirPublicKey) < 0) {
			firstKey = ourPublicKey;
			secondKey = theirPublicKey;
		} else {
			firstKey = theirPublicKey;
			secondKey = ourPublicKey;
		}

		ByteUtils.writeUint32(firstKey.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(firstKey, 0, firstKey.length);

		ByteUtils.writeUint32(secondKey.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(secondKey, 0, secondKey.length);
		for (byte[] input : additionalInputs) {
			ByteUtils.writeUint32(input.length, length, 0);
			digest.update(length, 0, length.length);
			digest.update(input, 0, input.length);
		}
		byte[] output = new byte[SecretKey.LENGTH];
		digest.doFinal(output, 0);

		return new SecretKey(output);
	}

	
	private int compareBytes(byte[] a, byte[] b) {
		int minLen = Math.min(a.length, b.length);
		for (int i = 0; i < minLen; i++) {
			int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
			if (diff != 0) return diff;
		}
		return a.length - b.length;
	}

	
	private boolean isAllZeros(byte[] bytes) {
		int acc = 0;
		for (byte b : bytes) {
			acc |= b;
		}
		return acc == 0;
	}

	
	static class HybridEncapsulation {
		private final byte[] ciphertext;
		private final byte[] sharedSecret;

		HybridEncapsulation(byte[] ciphertext, byte[] sharedSecret) {
			this.ciphertext = ciphertext;
			this.sharedSecret = sharedSecret;
		}

		
		byte[] getCiphertext() {
			return ciphertext;
		}

		
		byte[] getSharedSecret() {
			return sharedSecret;
		}

		
		void clearSecret() {
			Arrays.fill(sharedSecret, (byte) 0);
		}
	}
}
