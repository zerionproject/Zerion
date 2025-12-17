package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.HybridAgreementPrivateKey;
import org.briarproject.bramble.api.crypto.HybridAgreementPublicKey;
import org.briarproject.bramble.api.crypto.HybridEncapsulationResult;
import org.briarproject.bramble.api.crypto.HybridSignaturePrivateKey;
import org.briarproject.bramble.api.crypto.HybridSignaturePublicKey;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.KeyParser;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.system.SystemClock;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PRIVATE_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PRIVATE_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_AGREEMENT;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_SIGNATURE;
import static org.briarproject.bramble.api.crypto.PostQuantumConstants.ML_KEM_768_CIPHERTEXT_BYTES;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for post-quantum hybrid cryptography integration.
 * <p>
 * Tests the full integration of:
 * <ul>
 *   <li>ML-KEM-768 + X25519 hybrid key agreement</li>
 *   <li>ML-DSA-65 + Ed25519 hybrid signatures</li>
 *   <li>Key generation, parsing, and serialization</li>
 *   <li>Full key exchange round-trip</li>
 * </ul>
 */
public class HybridCryptographyTest extends BrambleMockTestCase {

	private static final String TEST_LABEL = "org.briarproject.bramble.test/HYBRID_TEST";
	private static final byte[] TEST_MESSAGE = "Test message for hybrid signature".getBytes();

	private final CryptoComponent crypto;

	public HybridCryptographyTest() {
		PasswordBasedKdf passwordBasedKdf = new ScryptKdf(new SystemClock());
		crypto = new CryptoComponentImpl(
				() -> null, // SecureRandomProvider returns null to use default
				passwordBasedKdf
		);
	}

	// ==================== Hybrid Key Agreement Tests ====================

	@Test
	public void testGenerateHybridAgreementKeyPair() {
		KeyPair keyPair = crypto.generateHybridAgreementKeyPair();

		assertNotNull(keyPair);
		assertNotNull(keyPair.getPublic());
		assertNotNull(keyPair.getPrivate());

		// Check key types
		assertEquals(KEY_TYPE_HYBRID_AGREEMENT, keyPair.getPublic().getKeyType());
		assertEquals(KEY_TYPE_HYBRID_AGREEMENT, keyPair.getPrivate().getKeyType());

		// Check key sizes
		assertEquals(HYBRID_AGREEMENT_PUBLIC_KEY_BYTES,
				keyPair.getPublic().getEncoded().length);
		assertEquals(HYBRID_AGREEMENT_PRIVATE_KEY_BYTES,
				keyPair.getPrivate().getEncoded().length);

		// Check correct types
		assertTrue(keyPair.getPublic() instanceof HybridAgreementPublicKey);
		assertTrue(keyPair.getPrivate() instanceof HybridAgreementPrivateKey);
	}

	@Test
	public void testHybridKeyAgreementRoundTrip() throws GeneralSecurityException {
		// Generate key pairs for Alice and Bob
		KeyPair aliceKeyPair = crypto.generateHybridAgreementKeyPair();
		KeyPair bobKeyPair = crypto.generateHybridAgreementKeyPair();

		// Bob encapsulates to Alice's public key
		HybridEncapsulationResult bobEncap = crypto.hybridEncapsulate(
				aliceKeyPair.getPublic());
		assertNotNull(bobEncap);
		assertEquals(ML_KEM_768_CIPHERTEXT_BYTES, bobEncap.getCiphertext().length);

		// Bob derives shared secret as responder
		SecretKey bobSecret = crypto.deriveHybridSharedSecretAsResponder(
				TEST_LABEL,
				aliceKeyPair.getPublic(),
				bobKeyPair,
				bobEncap.getSharedSecret()
		);

		// Alice derives shared secret as initiator (using Bob's ciphertext)
		SecretKey aliceSecret = crypto.deriveHybridSharedSecret(
				TEST_LABEL,
				bobKeyPair.getPublic(),
				aliceKeyPair,
				bobEncap.getCiphertext()
		);

		// Both should derive the same shared secret
		assertArrayEquals(aliceSecret.getBytes(), bobSecret.getBytes());

		// Clear secrets
		bobEncap.clearSecret();
	}

	@Test
	public void testHybridAgreementKeyParsing() throws GeneralSecurityException {
		// Generate a key pair
		KeyPair keyPair = crypto.generateHybridAgreementKeyPair();

		// Get the parser
		KeyParser parser = crypto.getHybridAgreementKeyParser();
		assertNotNull(parser);

		// Parse the public key
		byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
		PublicKey parsedPublicKey = parser.parsePublicKey(publicKeyBytes);
		assertArrayEquals(publicKeyBytes, parsedPublicKey.getEncoded());

		// Parse the private key
		byte[] privateKeyBytes = keyPair.getPrivate().getEncoded();
		PrivateKey parsedPrivateKey = parser.parsePrivateKey(privateKeyBytes);
		assertArrayEquals(privateKeyBytes, parsedPrivateKey.getEncoded());
	}

	@Test(expected = GeneralSecurityException.class)
	public void testHybridAgreementKeyParserRejectsInvalidSize()
			throws GeneralSecurityException {
		KeyParser parser = crypto.getHybridAgreementKeyParser();
		// Try to parse a classical-sized key (32 bytes) as hybrid
		byte[] wrongSizeKey = new byte[32];
		parser.parsePublicKey(wrongSizeKey);
	}

	// ==================== Hybrid Signature Tests ====================

	@Test
	public void testGenerateHybridSignatureKeyPair() {
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();

		assertNotNull(keyPair);
		assertNotNull(keyPair.getPublic());
		assertNotNull(keyPair.getPrivate());

		// Check key types
		assertEquals(KEY_TYPE_HYBRID_SIGNATURE, keyPair.getPublic().getKeyType());
		assertEquals(KEY_TYPE_HYBRID_SIGNATURE, keyPair.getPrivate().getKeyType());

		// Check key sizes
		assertEquals(HYBRID_SIGNATURE_PUBLIC_KEY_BYTES,
				keyPair.getPublic().getEncoded().length);
		assertEquals(HYBRID_SIGNATURE_PRIVATE_KEY_BYTES,
				keyPair.getPrivate().getEncoded().length);

		// Check correct types
		assertTrue(keyPair.getPublic() instanceof HybridSignaturePublicKey);
		assertTrue(keyPair.getPrivate() instanceof HybridSignaturePrivateKey);
	}

	@Test
	public void testHybridSignAndVerify() throws GeneralSecurityException {
		// Generate key pair
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();

		// Sign message
		byte[] signature = crypto.hybridSign(TEST_LABEL, TEST_MESSAGE,
				keyPair.getPrivate());

		assertNotNull(signature);
		assertEquals(HYBRID_SIGNATURE_BYTES, signature.length);

		// Verify signature
		boolean valid = crypto.verifyHybridSignature(signature, TEST_LABEL,
				TEST_MESSAGE, keyPair.getPublic());
		assertTrue("Valid signature should verify", valid);
	}

	@Test
	public void testHybridSignatureRejectsWrongKey() throws GeneralSecurityException {
		// Generate two key pairs
		KeyPair keyPair1 = crypto.generateHybridSignatureKeyPair();
		KeyPair keyPair2 = crypto.generateHybridSignatureKeyPair();

		// Sign with key 1
		byte[] signature = crypto.hybridSign(TEST_LABEL, TEST_MESSAGE,
				keyPair1.getPrivate());

		// Try to verify with key 2
		boolean valid = crypto.verifyHybridSignature(signature, TEST_LABEL,
				TEST_MESSAGE, keyPair2.getPublic());
		assertFalse("Signature with wrong key should not verify", valid);
	}

	@Test
	public void testHybridSignatureRejectsTamperedMessage()
			throws GeneralSecurityException {
		// Generate key pair
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();

		// Sign original message
		byte[] signature = crypto.hybridSign(TEST_LABEL, TEST_MESSAGE,
				keyPair.getPrivate());

		// Tamper with message
		byte[] tamperedMessage = Arrays.copyOf(TEST_MESSAGE, TEST_MESSAGE.length);
		tamperedMessage[0] ^= 0x01;

		// Try to verify with tampered message
		boolean valid = crypto.verifyHybridSignature(signature, TEST_LABEL,
				tamperedMessage, keyPair.getPublic());
		assertFalse("Signature with tampered message should not verify", valid);
	}

	@Test
	public void testHybridSignatureRejectsTamperedSignature()
			throws GeneralSecurityException {
		// Generate key pair
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();

		// Sign message
		byte[] signature = crypto.hybridSign(TEST_LABEL, TEST_MESSAGE,
				keyPair.getPrivate());

		// Tamper with signature
		signature[0] ^= 0x01;

		// Try to verify with tampered signature
		boolean valid = crypto.verifyHybridSignature(signature, TEST_LABEL,
				TEST_MESSAGE, keyPair.getPublic());
		assertFalse("Tampered signature should not verify", valid);
	}

	@Test
	public void testHybridSignatureKeyParsing() throws GeneralSecurityException {
		// Generate a key pair
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();

		// Get the parser
		KeyParser parser = crypto.getHybridSignatureKeyParser();
		assertNotNull(parser);

		// Parse the public key
		byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
		PublicKey parsedPublicKey = parser.parsePublicKey(publicKeyBytes);
		assertArrayEquals(publicKeyBytes, parsedPublicKey.getEncoded());

		// Parse the private key
		byte[] privateKeyBytes = keyPair.getPrivate().getEncoded();
		PrivateKey parsedPrivateKey = parser.parsePrivateKey(privateKeyBytes);
		assertArrayEquals(privateKeyBytes, parsedPrivateKey.getEncoded());
	}

	@Test(expected = GeneralSecurityException.class)
	public void testHybridSignatureKeyParserRejectsInvalidSize()
			throws GeneralSecurityException {
		KeyParser parser = crypto.getHybridSignatureKeyParser();
		// Try to parse a classical-sized key (32 bytes) as hybrid
		byte[] wrongSizeKey = new byte[32];
		parser.parsePublicKey(wrongSizeKey);
	}

	// ==================== Cross-Algorithm Isolation Tests ====================

	@Test(expected = IllegalArgumentException.class)
	public void testCannotUseAgreementKeyForSignature()
			throws GeneralSecurityException {
		KeyPair agreementKeyPair = crypto.generateHybridAgreementKeyPair();
		// This should throw - agreement keys cannot sign
		crypto.hybridSign(TEST_LABEL, TEST_MESSAGE, agreementKeyPair.getPrivate());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testCannotUseSignatureKeyForEncapsulation()
			throws GeneralSecurityException {
		KeyPair signatureKeyPair = crypto.generateHybridSignatureKeyPair();
		// This should throw - signature keys cannot encapsulate
		crypto.hybridEncapsulate(signatureKeyPair.getPublic());
	}

	// ==================== Key Component Extraction Tests ====================

	@Test
	public void testHybridAgreementKeyExtractsClassicalComponent() {
		KeyPair keyPair = crypto.generateHybridAgreementKeyPair();

		HybridAgreementPublicKey hybridPub =
				(HybridAgreementPublicKey) keyPair.getPublic();
		HybridAgreementPrivateKey hybridPriv =
				(HybridAgreementPrivateKey) keyPair.getPrivate();

		// Should be able to extract X25519 components
		byte[] x25519Pub = hybridPub.getX25519PublicKey();
		byte[] x25519Priv = hybridPriv.getX25519PrivateKey();

		assertEquals(32, x25519Pub.length);
		assertEquals(32, x25519Priv.length);

		// Should be able to extract ML-KEM components
		byte[] mlKemPub = hybridPub.getMlKemPublicKey();
		byte[] mlKemPriv = hybridPriv.getMlKemPrivateKey();

		assertEquals(1184, mlKemPub.length);
		assertEquals(2400, mlKemPriv.length);
	}

	@Test
	public void testHybridSignatureKeyExtractsClassicalComponent() {
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();

		HybridSignaturePublicKey hybridPub =
				(HybridSignaturePublicKey) keyPair.getPublic();
		HybridSignaturePrivateKey hybridPriv =
				(HybridSignaturePrivateKey) keyPair.getPrivate();

		// Should be able to extract Ed25519 components
		byte[] ed25519Pub = hybridPub.getEd25519PublicKey();
		byte[] ed25519Priv = hybridPriv.getEd25519PrivateKey();

		assertEquals(32, ed25519Pub.length);
		assertEquals(32, ed25519Priv.length);

		// Should be able to extract ML-DSA components
		byte[] mlDsaPub = hybridPub.getMlDsaPublicKey();
		byte[] mlDsaPriv = hybridPriv.getMlDsaPrivateKey();

		assertEquals(1952, mlDsaPub.length);
		assertEquals(4032, mlDsaPriv.length);
	}

	// ==================== Full Integration Test ====================

	@Test
	public void testFullHybridKeyExchangeWithSignedKeys()
			throws GeneralSecurityException {
		// This simulates a complete secure channel establishment with PQ crypto

		// Alice generates her identity (signature) and handshake (agreement) keys
		KeyPair aliceSignKey = crypto.generateHybridSignatureKeyPair();
		KeyPair aliceHandshakeKey = crypto.generateHybridAgreementKeyPair();

		// Bob generates his identity (signature) and handshake (agreement) keys
		KeyPair bobSignKey = crypto.generateHybridSignatureKeyPair();
		KeyPair bobHandshakeKey = crypto.generateHybridAgreementKeyPair();

		// Alice signs her handshake public key
		byte[] aliceSignedKey = crypto.hybridSign(
				"org.briarproject.bramble/HANDSHAKE_KEY",
				aliceHandshakeKey.getPublic().getEncoded(),
				aliceSignKey.getPrivate()
		);

		// Bob verifies Alice's signature on her handshake key
		boolean aliceKeyValid = crypto.verifyHybridSignature(
				aliceSignedKey,
				"org.briarproject.bramble/HANDSHAKE_KEY",
				aliceHandshakeKey.getPublic().getEncoded(),
				aliceSignKey.getPublic()
		);
		assertTrue("Alice's key signature should be valid", aliceKeyValid);

		// Bob signs his handshake public key
		byte[] bobSignedKey = crypto.hybridSign(
				"org.briarproject.bramble/HANDSHAKE_KEY",
				bobHandshakeKey.getPublic().getEncoded(),
				bobSignKey.getPrivate()
		);

		// Alice verifies Bob's signature on his handshake key
		boolean bobKeyValid = crypto.verifyHybridSignature(
				bobSignedKey,
				"org.briarproject.bramble/HANDSHAKE_KEY",
				bobHandshakeKey.getPublic().getEncoded(),
				bobSignKey.getPublic()
		);
		assertTrue("Bob's key signature should be valid", bobKeyValid);

		// Now perform hybrid key exchange
		// Bob encapsulates to Alice
		HybridEncapsulationResult bobEncap = crypto.hybridEncapsulate(
				aliceHandshakeKey.getPublic());

		// Both derive the shared secret
		SecretKey bobSecret = crypto.deriveHybridSharedSecretAsResponder(
				"org.briarproject.bramble/SESSION_KEY",
				aliceHandshakeKey.getPublic(),
				bobHandshakeKey,
				bobEncap.getSharedSecret()
		);

		SecretKey aliceSecret = crypto.deriveHybridSharedSecret(
				"org.briarproject.bramble/SESSION_KEY",
				bobHandshakeKey.getPublic(),
				aliceHandshakeKey,
				bobEncap.getCiphertext()
		);

		// Verify both parties derived the same secret
		assertArrayEquals(
				"Alice and Bob should have the same session key",
				aliceSecret.getBytes(),
				bobSecret.getBytes()
		);

		// Cleanup
		bobEncap.clearSecret();
	}
}
