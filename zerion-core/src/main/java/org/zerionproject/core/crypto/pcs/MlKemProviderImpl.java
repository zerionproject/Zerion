package org.zerionproject.core.crypto.pcs;

import org.bouncycastle.crypto.SecretWithEncapsulation;
import org.bouncycastle.crypto.digests.SHA3Digest;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMExtractor;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMKeyPairGenerator;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.mlkem.MLKEMPublicKeyParameters;
import org.zerionproject.core.api.crypto.pcs.MlKemEncapsulation;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.inject.Inject;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_EK_HASH_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_EK_SEED_SIZE;

@NotNullByDefault
class MlKemProviderImpl implements MlKemProvider {

	private final SecureRandom secureRandom;

	@Inject
	MlKemProviderImpl(SecureRandom secureRandom) {
		this.secureRandom = secureRandom;
	}

	@Override
	public MlKemKeyPair generateKeyPair() {
		MLKEMKeyPairGenerator generator = new MLKEMKeyPairGenerator();
		generator.init(new MLKEMKeyGenerationParameters(secureRandom,
				MLKEMParameters.ml_kem_768));

		org.bouncycastle.crypto.AsymmetricCipherKeyPair keyPair =
				generator.generateKeyPair();

		MLKEMPublicKeyParameters publicKey =
				(MLKEMPublicKeyParameters) keyPair.getPublic();
		MLKEMPrivateKeyParameters privateKey =
				(MLKEMPrivateKeyParameters) keyPair.getPrivate();

		byte[] encapsulationKey = publicKey.getEncoded();
		byte[] decapsulationKey = privateKey.getEncoded();

		byte[] ekSeed = Arrays.copyOf(encapsulationKey, MLKEM_EK_SEED_SIZE);
		byte[] ekVector = Arrays.copyOfRange(encapsulationKey,
				MLKEM_EK_SEED_SIZE, encapsulationKey.length);

		return new MlKemKeyPair(encapsulationKey, decapsulationKey,
				ekSeed, ekVector);
	}

	@Override
	public MlKemEncapsulation encapsulate(byte[] encapsulationKey) {
		MLKEMPublicKeyParameters publicKey = new MLKEMPublicKeyParameters(
				MLKEMParameters.ml_kem_768, encapsulationKey);

		MLKEMGenerator encapsulator = new MLKEMGenerator(secureRandom);
		SecretWithEncapsulation encapsulation =
				encapsulator.generateEncapsulated(publicKey);

		byte[] ciphertext = encapsulation.getEncapsulation();
		byte[] sharedSecret = encapsulation.getSecret();

		return new MlKemEncapsulation(ciphertext, sharedSecret);
	}

	@Override
	public byte[] decapsulate(byte[] decapsulationKey, byte[] ciphertext) {
		MLKEMPrivateKeyParameters privateKey = new MLKEMPrivateKeyParameters(
				MLKEMParameters.ml_kem_768, decapsulationKey);

		MLKEMExtractor extractor = new MLKEMExtractor(privateKey);
		return extractor.extractSecret(ciphertext);
	}

	@Override
	public byte[] hashEkSeedAndVector(byte[] ekSeed, byte[] ekVector) {
		SHA3Digest sha3 = new SHA3Digest(256);
		sha3.update(ekSeed, 0, ekSeed.length);
		sha3.update(ekVector, 0, ekVector.length);
		byte[] hash = new byte[sha3.getDigestSize()];
		sha3.doFinal(hash, 0);
		return hash;
	}

	@Override
	public boolean verifyEkHash(byte[] ekSeed, byte[] ekVector,
			byte[] expectedHash) {
		if (expectedHash.length != MLKEM_EK_HASH_SIZE) return false;
		byte[] computed = hashEkSeedAndVector(ekSeed, ekVector);
		return MessageDigest.isEqual(computed, expectedHash);
	}
}
