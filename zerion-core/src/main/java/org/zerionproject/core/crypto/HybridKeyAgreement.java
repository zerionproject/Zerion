package org.zerionproject.core.crypto;

import org.bouncycastle.crypto.digests.Blake2bDigest;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.util.ByteUtils;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;
import org.whispersystems.curve25519.Curve25519;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.annotation.concurrent.Immutable;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SHARED_SECRET_LABEL;
import static org.zerionproject.core.util.ByteUtils.INT_32_BYTES;

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
		byte[] ourX25519Priv = ourPrivateKey.getX25519PrivateKey();
		byte[] ourMlKemPriv = ourPrivateKey.getMlKemPrivateKey();
		byte[] x25519Secret = null;
		byte[] kemSecret = null;
		try {
			x25519Secret = curve25519.calculateAgreement(
					theirPublicKey.getX25519PublicKey(), ourX25519Priv);
			if (isAllZeros(x25519Secret)) {
				throw new GeneralSecurityException(
						"Invalid X25519 shared secret (all zeros - possible low-order point attack)");
			}
			kemSecret = mlKem768.decapsulate(ourMlKemPriv, kemCiphertext);
			return combineSecrets(label, x25519Secret, kemSecret,
					theirPublicKey.getEncoded(),
					ourKeyPair.getPublic().getEncoded(),
					inputs);
		} finally {
			Arrays.fill(ourX25519Priv, (byte) 0);
			Arrays.fill(ourMlKemPriv, (byte) 0);
			if (x25519Secret != null) Arrays.fill(x25519Secret, (byte) 0);
			if (kemSecret != null) Arrays.fill(kemSecret, (byte) 0);
		}
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
		byte[] ourX25519Priv = ourPrivateKey.getX25519PrivateKey();
		byte[] x25519Secret = null;
		try {
			x25519Secret = curve25519.calculateAgreement(
					theirPublicKey.getX25519PublicKey(), ourX25519Priv);
			if (isAllZeros(x25519Secret)) {
				throw new GeneralSecurityException(
						"Invalid X25519 shared secret (all zeros)");
			}
			return combineSecrets(label, x25519Secret, kemSecret,
					theirPublicKey.getEncoded(),
					ourKeyPair.getPublic().getEncoded(),
					inputs);
		} finally {
			Arrays.fill(ourX25519Priv, (byte) 0);
			if (x25519Secret != null) Arrays.fill(x25519Secret, (byte) 0);
		}
	}

	SecretKey deriveSharedSecretFsAsResponder(String label,
			HybridAgreementPublicKey theirStaticPublicKey,
			HybridAgreementPublicKey theirEphemeralPublicKey,
			KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair,
			byte[] kemSecret,
			byte[]... inputs) throws GeneralSecurityException {

		HybridAgreementPrivateKey ourStaticPrivate =
				(HybridAgreementPrivateKey) ourStaticKeyPair.getPrivate();
		HybridAgreementPrivateKey ourEphemeralPrivate =
				(HybridAgreementPrivateKey) ourEphemeralKeyPair.getPrivate();
		byte[] ourStaticX25519Priv = ourStaticPrivate.getX25519PrivateKey();
		byte[] ourEphemeralX25519Priv =
				ourEphemeralPrivate.getX25519PrivateKey();
		byte[] staticSecret = null;
		byte[] ephemeralSecret = null;
		try {
			staticSecret = curve25519.calculateAgreement(
					theirStaticPublicKey.getX25519PublicKey(),
					ourStaticX25519Priv);
			if (isAllZeros(staticSecret)) {
				throw new GeneralSecurityException(
						"Invalid static X25519 shared secret");
			}
			ephemeralSecret = curve25519.calculateAgreement(
					theirEphemeralPublicKey.getX25519PublicKey(),
					ourEphemeralX25519Priv);
			if (isAllZeros(ephemeralSecret)) {
				throw new GeneralSecurityException(
						"Invalid ephemeral X25519 shared secret");
			}
			return combineSecretsFs(label, staticSecret,
					ephemeralSecret, kemSecret,
					theirStaticPublicKey.getEncoded(),
					ourStaticKeyPair.getPublic().getEncoded(), inputs);
		} finally {
			Arrays.fill(ourStaticX25519Priv, (byte) 0);
			Arrays.fill(ourEphemeralX25519Priv, (byte) 0);
			if (staticSecret != null) Arrays.fill(staticSecret, (byte) 0);
			if (ephemeralSecret != null) Arrays.fill(ephemeralSecret, (byte) 0);
		}
	}

	SecretKey deriveSharedSecretFs(String label,
			HybridAgreementPublicKey theirStaticPublicKey,
			HybridAgreementPublicKey theirEphemeralPublicKey,
			KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair,
			byte[] kemCiphertext,
			byte[]... inputs) throws GeneralSecurityException {

		HybridAgreementPrivateKey ourStaticPrivate =
				(HybridAgreementPrivateKey) ourStaticKeyPair.getPrivate();
		HybridAgreementPrivateKey ourEphemeralPrivate =
				(HybridAgreementPrivateKey) ourEphemeralKeyPair.getPrivate();
		byte[] ourStaticX25519Priv = ourStaticPrivate.getX25519PrivateKey();
		byte[] ourEphemeralX25519Priv =
				ourEphemeralPrivate.getX25519PrivateKey();
		byte[] ourEphemeralMlKemPriv =
				ourEphemeralPrivate.getMlKemPrivateKey();
		byte[] staticSecret = null;
		byte[] ephemeralSecret = null;
		byte[] kemSecret = null;
		try {
			staticSecret = curve25519.calculateAgreement(
					theirStaticPublicKey.getX25519PublicKey(),
					ourStaticX25519Priv);
			if (isAllZeros(staticSecret)) {
				throw new GeneralSecurityException(
						"Invalid static X25519 shared secret");
			}
			ephemeralSecret = curve25519.calculateAgreement(
					theirEphemeralPublicKey.getX25519PublicKey(),
					ourEphemeralX25519Priv);
			if (isAllZeros(ephemeralSecret)) {
				throw new GeneralSecurityException(
						"Invalid ephemeral X25519 shared secret");
			}
			kemSecret = mlKem768.decapsulate(ourEphemeralMlKemPriv,
					kemCiphertext);
			return combineSecretsFs(label, staticSecret,
					ephemeralSecret, kemSecret,
					theirStaticPublicKey.getEncoded(),
					ourStaticKeyPair.getPublic().getEncoded(), inputs);
		} finally {
			Arrays.fill(ourStaticX25519Priv, (byte) 0);
			Arrays.fill(ourEphemeralX25519Priv, (byte) 0);
			Arrays.fill(ourEphemeralMlKemPriv, (byte) 0);
			if (staticSecret != null) Arrays.fill(staticSecret, (byte) 0);
			if (ephemeralSecret != null) Arrays.fill(ephemeralSecret, (byte) 0);
			if (kemSecret != null) Arrays.fill(kemSecret, (byte) 0);
		}
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

	private SecretKey combineSecretsFs(String label,
			byte[] staticX25519Secret,
			byte[] ephemeralX25519Secret,
			byte[] kemSecret,
			byte[] theirStaticPublicKey,
			byte[] ourStaticPublicKey,
			byte[]... additionalInputs) {
		Blake2bDigest digest = new Blake2bDigest(256);
		byte[] labelBytes = StringUtils.toUtf8(
				HYBRID_SHARED_SECRET_LABEL + "/" + label);
		byte[] length = new byte[INT_32_BYTES];
		ByteUtils.writeUint32(labelBytes.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(labelBytes, 0, labelBytes.length);
		ByteUtils.writeUint32(staticX25519Secret.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(staticX25519Secret, 0, staticX25519Secret.length);
		ByteUtils.writeUint32(ephemeralX25519Secret.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(ephemeralX25519Secret, 0, ephemeralX25519Secret.length);
		ByteUtils.writeUint32(kemSecret.length, length, 0);
		digest.update(length, 0, length.length);
		digest.update(kemSecret, 0, kemSecret.length);
		byte[] firstKey, secondKey;
		if (compareBytes(ourStaticPublicKey, theirStaticPublicKey) < 0) {
			firstKey = ourStaticPublicKey;
			secondKey = theirStaticPublicKey;
		} else {
			firstKey = theirStaticPublicKey;
			secondKey = ourStaticPublicKey;
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
		int result = a.length - b.length;
		for (int i = minLen - 1; i >= 0; i--) {
			int diff = (a[i] & 0xFF) - (b[i] & 0xFF);
			int mask = ~((diff | -diff) >>> 31) + 1;
			result = (diff & mask) | (result & ~mask);
		}
		return result;
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
