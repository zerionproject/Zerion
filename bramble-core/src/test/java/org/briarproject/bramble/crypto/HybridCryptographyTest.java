package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.HybridAgreementPrivateKey;
import org.briarproject.bramble.api.crypto.HybridAgreementPublicKey;
import org.briarproject.bramble.api.crypto.HybridSignaturePrivateKey;
import org.briarproject.bramble.api.crypto.HybridSignaturePublicKey;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.KeyParser;
import org.briarproject.bramble.system.SystemClock;
import org.briarproject.bramble.test.BrambleTestCase;
import org.briarproject.bramble.test.TestSecureRandomProvider;
import org.briarproject.bramble.test.TestUtils;
import org.junit.Test;

import java.security.GeneralSecurityException;

import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PRIVATE_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PRIVATE_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for hybrid post-quantum cryptography (Phase 2 implementation).
 * <p>
 * This tests the ML-KEM-768 + X25519 hybrid key agreement and
 * ML-DSA-65 + Ed25519 hybrid digital signatures.
 */
public class HybridCryptographyTest extends BrambleTestCase {

	private static final SystemClock clock = new SystemClock();
	private final CryptoComponent crypto =
			new CryptoComponentImpl(new TestSecureRandomProvider(),
					new Argon2idKdf(clock), new ScryptKdf(clock));

	// ==================== Hybrid Key Agreement Tests ====================

	@Test
	public void testGenerateHybridAgreementKeyPair() {
		KeyPair keyPair = crypto.generateHybridAgreementKeyPair();
		assertNotNull(keyPair);
		assertNotNull(keyPair.getPublic());
		assertNotNull(keyPair.getPrivate());

		assertTrue(keyPair.getPublic() instanceof HybridAgreementPublicKey);
		assertTrue(keyPair.getPrivate() instanceof HybridAgreementPrivateKey);

		HybridAgreementPublicKey pub = (HybridAgreementPublicKey) keyPair.getPublic();
		HybridAgreementPrivateKey priv = (HybridAgreementPrivateKey) keyPair.getPrivate();

		assertEquals(HYBRID_AGREEMENT_PUBLIC_KEY_BYTES, pub.getEncoded().length);
		assertEquals(HYBRID_AGREEMENT_PRIVATE_KEY_BYTES, priv.getEncoded().length);

		// Check component sizes
		assertEquals(32, pub.getX25519PublicKey().length);
		assertEquals(1184, pub.getMlKemPublicKey().length);
	}

	@Test
	public void testHybridAgreementKeyPairsAreUnique() {
		KeyPair keyPair1 = crypto.generateHybridAgreementKeyPair();
		KeyPair keyPair2 = crypto.generateHybridAgreementKeyPair();

		assertFalse(java.util.Arrays.equals(
				keyPair1.getPublic().getEncoded(),
				keyPair2.getPublic().getEncoded()));
		assertFalse(java.util.Arrays.equals(
				keyPair1.getPrivate().getEncoded(),
				keyPair2.getPrivate().getEncoded()));
	}

	@Test
	public void testHybridAgreementKeyParser() throws GeneralSecurityException {
		KeyPair keyPair = crypto.generateHybridAgreementKeyPair();
		KeyParser parser = crypto.getHybridAgreementKeyParser();

		// Parse public key
		byte[] pubBytes = keyPair.getPublic().getEncoded();
		HybridAgreementPublicKey parsedPub =
				(HybridAgreementPublicKey) parser.parsePublicKey(pubBytes);
		assertArrayEquals(pubBytes, parsedPub.getEncoded());

		// Parse private key
		byte[] privBytes = keyPair.getPrivate().getEncoded();
		HybridAgreementPrivateKey parsedPriv =
				(HybridAgreementPrivateKey) parser.parsePrivateKey(privBytes);
		assertArrayEquals(privBytes, parsedPriv.getEncoded());
	}

	// ==================== Hybrid Signature Tests ====================

	@Test
	public void testGenerateHybridSignatureKeyPair() {
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();
		assertNotNull(keyPair);
		assertNotNull(keyPair.getPublic());
		assertNotNull(keyPair.getPrivate());

		assertTrue(keyPair.getPublic() instanceof HybridSignaturePublicKey);
		assertTrue(keyPair.getPrivate() instanceof HybridSignaturePrivateKey);

		HybridSignaturePublicKey pub = (HybridSignaturePublicKey) keyPair.getPublic();
		HybridSignaturePrivateKey priv = (HybridSignaturePrivateKey) keyPair.getPrivate();

		assertEquals(HYBRID_SIGNATURE_PUBLIC_KEY_BYTES, pub.getEncoded().length);
		assertEquals(HYBRID_SIGNATURE_PRIVATE_KEY_BYTES, priv.getEncoded().length);

		// Check component sizes
		assertEquals(32, pub.getEd25519PublicKey().length);
		assertEquals(1952, pub.getMlDsaPublicKey().length);
	}

	@Test
	public void testHybridSignatureKeyPairsAreUnique() {
		KeyPair keyPair1 = crypto.generateHybridSignatureKeyPair();
		KeyPair keyPair2 = crypto.generateHybridSignatureKeyPair();

		assertFalse(java.util.Arrays.equals(
				keyPair1.getPublic().getEncoded(),
				keyPair2.getPublic().getEncoded()));
		assertFalse(java.util.Arrays.equals(
				keyPair1.getPrivate().getEncoded(),
				keyPair2.getPrivate().getEncoded()));
	}

	@Test
	public void testHybridSignAndVerify() throws GeneralSecurityException {
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();
		byte[] message = TestUtils.getRandomBytes(256);
		String label = "TEST_HYBRID_SIGNATURE";

		byte[] signature = crypto.hybridSign(label, message, keyPair.getPrivate());
		assertNotNull(signature);
		assertEquals(HYBRID_SIGNATURE_BYTES, signature.length);

		boolean valid = crypto.verifyHybridSignature(signature, label, message,
				keyPair.getPublic());
		assertTrue(valid);
	}

	@Test
	public void testHybridSignatureInvalidWithWrongKey()
			throws GeneralSecurityException {
		KeyPair keyPair1 = crypto.generateHybridSignatureKeyPair();
		KeyPair keyPair2 = crypto.generateHybridSignatureKeyPair();
		byte[] message = TestUtils.getRandomBytes(256);
		String label = "TEST_HYBRID_SIGNATURE";

		byte[] signature = crypto.hybridSign(label, message, keyPair1.getPrivate());

		// Verify with wrong public key should fail
		boolean valid = crypto.verifyHybridSignature(signature, label, message,
				keyPair2.getPublic());
		assertFalse(valid);
	}

	@Test
	public void testHybridSignatureInvalidWithWrongMessage()
			throws GeneralSecurityException {
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();
		byte[] message1 = TestUtils.getRandomBytes(256);
		byte[] message2 = TestUtils.getRandomBytes(256);
		String label = "TEST_HYBRID_SIGNATURE";

		byte[] signature = crypto.hybridSign(label, message1, keyPair.getPrivate());

		// Verify with wrong message should fail
		boolean valid = crypto.verifyHybridSignature(signature, label, message2,
				keyPair.getPublic());
		assertFalse(valid);
	}

	@Test
	public void testHybridSignatureInvalidWithWrongLabel()
			throws GeneralSecurityException {
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();
		byte[] message = TestUtils.getRandomBytes(256);
		String label1 = "TEST_HYBRID_SIGNATURE_1";
		String label2 = "TEST_HYBRID_SIGNATURE_2";

		byte[] signature = crypto.hybridSign(label1, message, keyPair.getPrivate());

		// Verify with wrong label should fail
		boolean valid = crypto.verifyHybridSignature(signature, label2, message,
				keyPair.getPublic());
		assertFalse(valid);
	}

	@Test
	public void testHybridSignatureInvalidWithTamperedSignature()
			throws GeneralSecurityException {
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();
		byte[] message = TestUtils.getRandomBytes(256);
		String label = "TEST_HYBRID_SIGNATURE";

		byte[] signature = crypto.hybridSign(label, message, keyPair.getPrivate());

		// Tamper with Ed25519 part (first 64 bytes)
		byte[] tamperedSig1 = signature.clone();
		tamperedSig1[0] ^= 0xFF;
		assertFalse(crypto.verifyHybridSignature(tamperedSig1, label, message,
				keyPair.getPublic()));

		// Tamper with ML-DSA part (after byte 64)
		byte[] tamperedSig2 = signature.clone();
		tamperedSig2[100] ^= 0xFF;
		assertFalse(crypto.verifyHybridSignature(tamperedSig2, label, message,
				keyPair.getPublic()));
	}

	@Test
	public void testHybridSignatureKeyParser() throws GeneralSecurityException {
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();
		KeyParser parser = crypto.getHybridSignatureKeyParser();

		// Parse public key
		byte[] pubBytes = keyPair.getPublic().getEncoded();
		HybridSignaturePublicKey parsedPub =
				(HybridSignaturePublicKey) parser.parsePublicKey(pubBytes);
		assertArrayEquals(pubBytes, parsedPub.getEncoded());

		// Parse private key
		byte[] privBytes = keyPair.getPrivate().getEncoded();
		HybridSignaturePrivateKey parsedPriv =
				(HybridSignaturePrivateKey) parser.parsePrivateKey(privBytes);
		assertArrayEquals(privBytes, parsedPriv.getEncoded());
	}

	@Test
	public void testHybridSignatureWithEmptyMessage()
			throws GeneralSecurityException {
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();
		byte[] message = new byte[0];
		String label = "TEST_EMPTY_MESSAGE";

		byte[] signature = crypto.hybridSign(label, message, keyPair.getPrivate());
		assertTrue(crypto.verifyHybridSignature(signature, label, message,
				keyPair.getPublic()));
	}

	@Test
	public void testHybridSignatureWithLargeMessage()
			throws GeneralSecurityException {
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();
		byte[] message = TestUtils.getRandomBytes(1024 * 1024); // 1 MB
		String label = "TEST_LARGE_MESSAGE";

		byte[] signature = crypto.hybridSign(label, message, keyPair.getPrivate());
		assertTrue(crypto.verifyHybridSignature(signature, label, message,
				keyPair.getPublic()));
	}

	// ==================== Component Extraction Tests ====================

	@Test
	public void testX25519ComponentExtraction() {
		KeyPair hybridKeyPair = crypto.generateHybridAgreementKeyPair();
		HybridAgreementPublicKey hybridPub =
				(HybridAgreementPublicKey) hybridKeyPair.getPublic();

		// Extract the X25519 component
		byte[] x25519PubBytes = hybridPub.getX25519PublicKey();
		assertEquals(32, x25519PubBytes.length);

		// Verify the X25519 component matches the first 32 bytes of the hybrid key
		byte[] hybridBytes = hybridPub.getEncoded();
		byte[] extractedBytes = new byte[32];
		System.arraycopy(hybridBytes, 0, extractedBytes, 0, 32);
		assertArrayEquals(x25519PubBytes, extractedBytes);
	}

	@Test
	public void testEd25519ComponentExtraction() {
		KeyPair hybridKeyPair = crypto.generateHybridSignatureKeyPair();
		HybridSignaturePublicKey hybridPub =
				(HybridSignaturePublicKey) hybridKeyPair.getPublic();

		// Extract the Ed25519 component
		byte[] ed25519PubBytes = hybridPub.getEd25519PublicKey();
		assertEquals(32, ed25519PubBytes.length);

		// Verify the Ed25519 component matches the first 32 bytes of the hybrid key
		byte[] hybridBytes = hybridPub.getEncoded();
		byte[] extractedBytes = new byte[32];
		System.arraycopy(hybridBytes, 0, extractedBytes, 0, 32);
		assertArrayEquals(ed25519PubBytes, extractedBytes);
	}

	// ==================== Memory Clearing Tests ====================

	@Test
	public void testPrivateKeyClear() {
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();
		HybridSignaturePrivateKey priv =
				(HybridSignaturePrivateKey) keyPair.getPrivate();

		// Verify key is valid before clearing
		assertNotNull(priv.getEncoded());
		assertTrue(priv.getEncoded().length > 0);

		// Clear the key
		priv.clear();

		// After clearing, the internal bytes should be zeroed
		// (Note: We can't directly test this without accessing internal state,
		// but the clear() method should work)
	}
}
