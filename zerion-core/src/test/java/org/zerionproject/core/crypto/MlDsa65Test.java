package org.zerionproject.core.crypto;

import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Test;

import java.security.SecureRandom;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_PRIVATE_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_DSA_65_SIGNATURE_BYTES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MlDsa65Test extends BrambleTestCase {

	private final SecureRandom secureRandom = new TestSecureRandomProvider()
			.getProvider() != null ? new SecureRandom() : new SecureRandom();
	private final MlDsa65 mlDsa65 = new MlDsa65(secureRandom);

	@Test
	public void testKeyGeneration() {
		MlDsa65.MlDsaKeyPair keyPair = mlDsa65.generateKeyPair();
		assertNotNull(keyPair);
		assertNotNull(keyPair.getPublicKey());
		assertNotNull(keyPair.getPrivateKey());

		System.out.println("ML-DSA-65 Public key length: " + keyPair.getPublicKey().length);
		System.out.println("ML-DSA-65 Private key length: " + keyPair.getPrivateKey().length);
		System.out.println("Expected public key length: " + ML_DSA_65_PUBLIC_KEY_BYTES);
		System.out.println("Expected private key length: " + ML_DSA_65_PRIVATE_KEY_BYTES);

		assertEquals(ML_DSA_65_PUBLIC_KEY_BYTES, keyPair.getPublicKey().length);
		assertEquals(ML_DSA_65_PRIVATE_KEY_BYTES, keyPair.getPrivateKey().length);
	}

	@Test
	public void testSignAndVerify() throws Exception {
		MlDsa65.MlDsaKeyPair keyPair = mlDsa65.generateKeyPair();
		byte[] message = "Test message for ML-DSA-65".getBytes();

		System.out.println("Actual public key length: " + keyPair.getPublicKey().length);
		System.out.println("Actual private key length: " + keyPair.getPrivateKey().length);

		byte[] signature = mlDsa65.sign(keyPair.getPrivateKey(), message);
		assertNotNull(signature);

		System.out.println("ML-DSA-65 Signature length: " + signature.length);
		System.out.println("Expected signature length: " + ML_DSA_65_SIGNATURE_BYTES);

		System.out.println("Signature length matches: " + (signature.length == ML_DSA_65_SIGNATURE_BYTES));

		boolean valid = mlDsa65.verify(keyPair.getPublicKey(), message, signature);
		assertTrue("Signature should be valid", valid);

		assertEquals("Signature length should match constant",
				ML_DSA_65_SIGNATURE_BYTES, signature.length);
	}
}
