package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.zerionproject.core.test.TestUtils;
import org.zerionproject.core.util.StringUtils;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public abstract class SignatureTest extends BrambleTestCase {

	protected final CryptoComponent crypto;

	private final PublicKey publicKey;
	private final PrivateKey privateKey;
	private final String label = StringUtils.getRandomString(42);
	private final byte[] inputBytes = TestUtils.getRandomBytes(123);

	protected abstract KeyPair generateKeyPair();

	protected abstract byte[] sign(String label, byte[] toSign,
			PrivateKey privateKey) throws GeneralSecurityException;

	protected abstract boolean verify(byte[] signature, String label,
			byte[] signed, PublicKey publicKey) throws GeneralSecurityException;

	SignatureTest() {
		crypto = new CryptoComponentImpl(new TestSecureRandomProvider(), null);
		KeyPair k = generateKeyPair();
		publicKey = k.getPublic();
		privateKey = k.getPrivate();
	}

	@Test
	public void testIdenticalKeysAndInputsProduceIdenticalSignatures()
			throws Exception {

		byte[] sig1 = sign(label, inputBytes, privateKey);
		byte[] sig2 = sign(label, inputBytes, privateKey);
		assertArrayEquals(sig1, sig2);
	}

	@Test
	public void testDifferentKeysProduceDifferentSignatures() throws Exception {

		KeyPair k2 = generateKeyPair();
		PrivateKey privateKey2 = k2.getPrivate();

		byte[] sig1 = sign(label, inputBytes, privateKey);
		byte[] sig2 = sign(label, inputBytes, privateKey2);
		assertFalse(Arrays.equals(sig1, sig2));
	}

	@Test
	public void testDifferentInputsProduceDifferentSignatures()
			throws Exception {

		byte[] inputBytes2 = TestUtils.getRandomBytes(123);

		byte[] sig1 = sign(label, inputBytes, privateKey);
		byte[] sig2 = sign(label, inputBytes2, privateKey);
		assertFalse(Arrays.equals(sig1, sig2));
	}

	@Test
	public void testDifferentLabelsProduceDifferentSignatures()
			throws Exception {

		String label2 = StringUtils.getRandomString(42);

		byte[] sig1 = sign(label, inputBytes, privateKey);
		byte[] sig2 = sign(label2, inputBytes, privateKey);
		assertFalse(Arrays.equals(sig1, sig2));
	}

	@Test
	public void testSignatureVerification() throws Exception {
		byte[] sig = sign(label, inputBytes, privateKey);
		assertTrue(verify(sig, label, inputBytes, publicKey));
	}

	@Test
	public void testDifferentKeyFailsVerification() throws Exception {

		KeyPair k2 = generateKeyPair();
		PrivateKey privateKey2 = k2.getPrivate();

		byte[] sig = sign(label, inputBytes, privateKey2);
		assertFalse(verify(sig, label, inputBytes, publicKey));
	}

	@Test
	public void testDifferentInputFailsVerification() throws Exception {

		byte[] inputBytes2 = TestUtils.getRandomBytes(123);

		byte[] sig = sign(label, inputBytes, privateKey);
		assertFalse(verify(sig, label, inputBytes2, publicKey));
	}

	@Test
	public void testDifferentLabelFailsVerification() throws Exception {

		String label2 = StringUtils.getRandomString(42);

		byte[] sig = sign(label, inputBytes, privateKey);
		assertFalse(verify(sig, label2, inputBytes, publicKey));
	}

}
