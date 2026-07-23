package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.api.crypto.HybridEncapsulationResult;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PRIVATE_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PRIVATE_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_AGREEMENT;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.KEY_TYPE_HYBRID_SIGNATURE;
import static org.zerionproject.core.api.crypto.PostQuantumConstants.ML_KEM_768_CIPHERTEXT_BYTES;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class HybridCryptographyTest extends BrambleMockTestCase {

	private static final String TEST_LABEL = "org.zerionproject.core.test/HYBRID_TEST";
	private static final byte[] TEST_MESSAGE = "Test message for hybrid signature".getBytes();

	private final CryptoComponent crypto;

	public HybridCryptographyTest() {
		PasswordBasedKdf passwordBasedKdf = new ScryptKdf(new SystemClock());
		crypto = new CryptoComponentImpl(
				() -> null,
				passwordBasedKdf
		);
	}

	@Test
	public void testGenerateHybridAgreementKeyPair() {
		KeyPair keyPair = crypto.generateHybridAgreementKeyPair();

		assertNotNull(keyPair);
		assertNotNull(keyPair.getPublic());
		assertNotNull(keyPair.getPrivate());

		assertEquals(KEY_TYPE_HYBRID_AGREEMENT, keyPair.getPublic().getKeyType());
		assertEquals(KEY_TYPE_HYBRID_AGREEMENT, keyPair.getPrivate().getKeyType());

		assertEquals(HYBRID_AGREEMENT_PUBLIC_KEY_BYTES,
				keyPair.getPublic().getEncoded().length);
		assertEquals(HYBRID_AGREEMENT_PRIVATE_KEY_BYTES,
				keyPair.getPrivate().getEncoded().length);

		assertTrue(keyPair.getPublic() instanceof HybridAgreementPublicKey);
		assertTrue(keyPair.getPrivate() instanceof HybridAgreementPrivateKey);
	}

	@Test
	public void testHybridKeyAgreementRoundTrip() throws GeneralSecurityException {

		KeyPair aliceKeyPair = crypto.generateHybridAgreementKeyPair();
		KeyPair bobKeyPair = crypto.generateHybridAgreementKeyPair();

		HybridEncapsulationResult bobEncap = crypto.hybridEncapsulate(
				aliceKeyPair.getPublic());
		assertNotNull(bobEncap);
		assertEquals(ML_KEM_768_CIPHERTEXT_BYTES, bobEncap.getCiphertext().length);

		SecretKey bobSecret = crypto.deriveHybridSharedSecretAsResponder(
				TEST_LABEL,
				aliceKeyPair.getPublic(),
				bobKeyPair,
				bobEncap.getSharedSecret()
		);

		SecretKey aliceSecret = crypto.deriveHybridSharedSecret(
				TEST_LABEL,
				bobKeyPair.getPublic(),
				aliceKeyPair,
				bobEncap.getCiphertext()
		);

		assertArrayEquals(aliceSecret.getBytes(), bobSecret.getBytes());

		bobEncap.clearSecret();
	}

	@Test
	public void testHybridAgreementKeyParsing() throws GeneralSecurityException {

		KeyPair keyPair = crypto.generateHybridAgreementKeyPair();

		KeyParser parser = crypto.getHybridAgreementKeyParser();
		assertNotNull(parser);

		byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
		PublicKey parsedPublicKey = parser.parsePublicKey(publicKeyBytes);
		assertArrayEquals(publicKeyBytes, parsedPublicKey.getEncoded());

		byte[] privateKeyBytes = keyPair.getPrivate().getEncoded();
		PrivateKey parsedPrivateKey = parser.parsePrivateKey(privateKeyBytes);
		assertArrayEquals(privateKeyBytes, parsedPrivateKey.getEncoded());
	}

	@Test(expected = GeneralSecurityException.class)
	public void testHybridAgreementKeyParserRejectsInvalidSize()
			throws GeneralSecurityException {
		KeyParser parser = crypto.getHybridAgreementKeyParser();

		byte[] wrongSizeKey = new byte[32];
		parser.parsePublicKey(wrongSizeKey);
	}

	@Test
	public void testGenerateHybridSignatureKeyPair() {
		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();

		assertNotNull(keyPair);
		assertNotNull(keyPair.getPublic());
		assertNotNull(keyPair.getPrivate());

		assertEquals(KEY_TYPE_HYBRID_SIGNATURE, keyPair.getPublic().getKeyType());
		assertEquals(KEY_TYPE_HYBRID_SIGNATURE, keyPair.getPrivate().getKeyType());

		assertEquals(HYBRID_SIGNATURE_PUBLIC_KEY_BYTES,
				keyPair.getPublic().getEncoded().length);
		assertEquals(HYBRID_SIGNATURE_PRIVATE_KEY_BYTES,
				keyPair.getPrivate().getEncoded().length);

		assertTrue(keyPair.getPublic() instanceof HybridSignaturePublicKey);
		assertTrue(keyPair.getPrivate() instanceof HybridSignaturePrivateKey);
	}

	@Test
	public void testHybridSignAndVerify() throws GeneralSecurityException {

		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();

		byte[] signature = crypto.hybridSign(TEST_LABEL, TEST_MESSAGE,
				keyPair.getPrivate());

		assertNotNull(signature);
		assertEquals(HYBRID_SIGNATURE_BYTES, signature.length);

		boolean valid = crypto.verifyHybridSignature(signature, TEST_LABEL,
				TEST_MESSAGE, keyPair.getPublic());
		assertTrue("Valid signature should verify", valid);
	}

	@Test
	public void testHybridSignatureRejectsWrongKey() throws GeneralSecurityException {

		KeyPair keyPair1 = crypto.generateHybridSignatureKeyPair();
		KeyPair keyPair2 = crypto.generateHybridSignatureKeyPair();

		byte[] signature = crypto.hybridSign(TEST_LABEL, TEST_MESSAGE,
				keyPair1.getPrivate());

		boolean valid = crypto.verifyHybridSignature(signature, TEST_LABEL,
				TEST_MESSAGE, keyPair2.getPublic());
		assertFalse("Signature with wrong key should not verify", valid);
	}

	@Test
	public void testHybridSignatureRejectsTamperedMessage()
			throws GeneralSecurityException {

		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();

		byte[] signature = crypto.hybridSign(TEST_LABEL, TEST_MESSAGE,
				keyPair.getPrivate());

		byte[] tamperedMessage = Arrays.copyOf(TEST_MESSAGE, TEST_MESSAGE.length);
		tamperedMessage[0] ^= 0x01;

		boolean valid = crypto.verifyHybridSignature(signature, TEST_LABEL,
				tamperedMessage, keyPair.getPublic());
		assertFalse("Signature with tampered message should not verify", valid);
	}

	@Test
	public void testHybridSignatureRejectsTamperedSignature()
			throws GeneralSecurityException {

		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();

		byte[] signature = crypto.hybridSign(TEST_LABEL, TEST_MESSAGE,
				keyPair.getPrivate());

		signature[0] ^= 0x01;

		boolean valid = crypto.verifyHybridSignature(signature, TEST_LABEL,
				TEST_MESSAGE, keyPair.getPublic());
		assertFalse("Tampered signature should not verify", valid);
	}

	@Test
	public void testHybridSignatureKeyParsing() throws GeneralSecurityException {

		KeyPair keyPair = crypto.generateHybridSignatureKeyPair();

		KeyParser parser = crypto.getHybridSignatureKeyParser();
		assertNotNull(parser);

		byte[] publicKeyBytes = keyPair.getPublic().getEncoded();
		PublicKey parsedPublicKey = parser.parsePublicKey(publicKeyBytes);
		assertArrayEquals(publicKeyBytes, parsedPublicKey.getEncoded());

		byte[] privateKeyBytes = keyPair.getPrivate().getEncoded();
		PrivateKey parsedPrivateKey = parser.parsePrivateKey(privateKeyBytes);
		assertArrayEquals(privateKeyBytes, parsedPrivateKey.getEncoded());
	}

	@Test(expected = GeneralSecurityException.class)
	public void testHybridSignatureKeyParserRejectsInvalidSize()
			throws GeneralSecurityException {
		KeyParser parser = crypto.getHybridSignatureKeyParser();

		byte[] wrongSizeKey = new byte[32];
		parser.parsePublicKey(wrongSizeKey);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testCannotUseAgreementKeyForSignature()
			throws GeneralSecurityException {
		KeyPair agreementKeyPair = crypto.generateHybridAgreementKeyPair();

		crypto.hybridSign(TEST_LABEL, TEST_MESSAGE, agreementKeyPair.getPrivate());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testCannotUseSignatureKeyForEncapsulation()
			throws GeneralSecurityException {
		KeyPair signatureKeyPair = crypto.generateHybridSignatureKeyPair();

		crypto.hybridEncapsulate(signatureKeyPair.getPublic());
	}

	@Test
	public void testHybridAgreementKeyExtractsClassicalComponent() {
		KeyPair keyPair = crypto.generateHybridAgreementKeyPair();

		HybridAgreementPublicKey hybridPub =
				(HybridAgreementPublicKey) keyPair.getPublic();
		HybridAgreementPrivateKey hybridPriv =
				(HybridAgreementPrivateKey) keyPair.getPrivate();

		byte[] x25519Pub = hybridPub.getX25519PublicKey();
		byte[] x25519Priv = hybridPriv.getX25519PrivateKey();

		assertEquals(32, x25519Pub.length);
		assertEquals(32, x25519Priv.length);

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

		byte[] ed25519Pub = hybridPub.getEd25519PublicKey();
		byte[] ed25519Priv = hybridPriv.getEd25519PrivateKey();

		assertEquals(32, ed25519Pub.length);
		assertEquals(32, ed25519Priv.length);

		byte[] mlDsaPub = hybridPub.getMlDsaPublicKey();
		byte[] mlDsaPriv = hybridPriv.getMlDsaPrivateKey();

		assertEquals(1952, mlDsaPub.length);
		assertEquals(4032, mlDsaPriv.length);
	}

	@Test
	public void testFullHybridKeyExchangeWithSignedKeys()
			throws GeneralSecurityException {

		KeyPair aliceSignKey = crypto.generateHybridSignatureKeyPair();
		KeyPair aliceHandshakeKey = crypto.generateHybridAgreementKeyPair();

		KeyPair bobSignKey = crypto.generateHybridSignatureKeyPair();
		KeyPair bobHandshakeKey = crypto.generateHybridAgreementKeyPair();

		byte[] aliceSignedKey = crypto.hybridSign(
				"org.zerionproject.core/HANDSHAKE_KEY",
				aliceHandshakeKey.getPublic().getEncoded(),
				aliceSignKey.getPrivate()
		);

		boolean aliceKeyValid = crypto.verifyHybridSignature(
				aliceSignedKey,
				"org.zerionproject.core/HANDSHAKE_KEY",
				aliceHandshakeKey.getPublic().getEncoded(),
				aliceSignKey.getPublic()
		);
		assertTrue("Alice's key signature should be valid", aliceKeyValid);

		byte[] bobSignedKey = crypto.hybridSign(
				"org.zerionproject.core/HANDSHAKE_KEY",
				bobHandshakeKey.getPublic().getEncoded(),
				bobSignKey.getPrivate()
		);

		boolean bobKeyValid = crypto.verifyHybridSignature(
				bobSignedKey,
				"org.zerionproject.core/HANDSHAKE_KEY",
				bobHandshakeKey.getPublic().getEncoded(),
				bobSignKey.getPublic()
		);
		assertTrue("Bob's key signature should be valid", bobKeyValid);

		HybridEncapsulationResult bobEncap = crypto.hybridEncapsulate(
				aliceHandshakeKey.getPublic());

		SecretKey bobSecret = crypto.deriveHybridSharedSecretAsResponder(
				"org.zerionproject.core/SESSION_KEY",
				aliceHandshakeKey.getPublic(),
				bobHandshakeKey,
				bobEncap.getSharedSecret()
		);

		SecretKey aliceSecret = crypto.deriveHybridSharedSecret(
				"org.zerionproject.core/SESSION_KEY",
				bobHandshakeKey.getPublic(),
				aliceHandshakeKey,
				bobEncap.getCiphertext()
		);

		assertArrayEquals(
				"Alice and Bob should have the same session key",
				aliceSecret.getBytes(),
				bobSecret.getBytes()
		);

		bobEncap.clearSecret();
	}

	@Test
	public void testHybridFsKeyAgreementRoundTrip()
			throws GeneralSecurityException {
		KeyPair aliceStatic = crypto.generateHybridAgreementKeyPair();
		KeyPair bobStatic = crypto.generateHybridAgreementKeyPair();
		KeyPair aliceEph = crypto.generateHybridAgreementKeyPair();
		KeyPair bobEph = crypto.generateHybridAgreementKeyPair();

		HybridEncapsulationResult enc =
				crypto.hybridEncapsulate(bobEph.getPublic());
		byte[][] inputs = {
				aliceStatic.getPublic().getEncoded(),
				bobStatic.getPublic().getEncoded(),
				aliceEph.getPublic().getEncoded(),
				bobEph.getPublic().getEncoded(),
				enc.getCiphertext(),
				new byte[] {2},
				new byte[] {2}
		};

		SecretKey aliceSecret = crypto.deriveHybridSharedSecretFsAsResponder(
				TEST_LABEL,
				bobStatic.getPublic(), bobEph.getPublic(),
				aliceStatic, aliceEph,
				enc.getSharedSecret(), inputs);

		SecretKey bobSecret = crypto.deriveHybridSharedSecretFs(
				TEST_LABEL,
				aliceStatic.getPublic(), aliceEph.getPublic(),
				bobStatic, bobEph,
				enc.getCiphertext(), inputs);

		assertArrayEquals(
				"FS handshake initiator and responder must derive the same key",
				aliceSecret.getBytes(), bobSecret.getBytes());
		enc.clearSecret();
	}

	@Test
	public void testHybridFsKeyDependsOnEphemeralKeys()
			throws GeneralSecurityException {
		KeyPair aliceStatic = crypto.generateHybridAgreementKeyPair();
		KeyPair bobStatic = crypto.generateHybridAgreementKeyPair();
		KeyPair aliceEph = crypto.generateHybridAgreementKeyPair();
		KeyPair bobEph1 = crypto.generateHybridAgreementKeyPair();
		KeyPair bobEph2 = crypto.generateHybridAgreementKeyPair();

		HybridEncapsulationResult enc1 =
				crypto.hybridEncapsulate(bobEph1.getPublic());
		SecretKey key1 = crypto.deriveHybridSharedSecretFsAsResponder(
				TEST_LABEL, bobStatic.getPublic(), bobEph1.getPublic(),
				aliceStatic, aliceEph, enc1.getSharedSecret(),
				enc1.getCiphertext());

		HybridEncapsulationResult enc2 =
				crypto.hybridEncapsulate(bobEph2.getPublic());
		SecretKey key2 = crypto.deriveHybridSharedSecretFsAsResponder(
				TEST_LABEL, bobStatic.getPublic(), bobEph2.getPublic(),
				aliceStatic, aliceEph, enc2.getSharedSecret(),
				enc2.getCiphertext());

		assertFalse("Changing ephemeral keys must change the derived key",
				Arrays.equals(key1.getBytes(), key2.getBytes()));
		enc1.clearSecret();
		enc2.clearSecret();
	}
}
