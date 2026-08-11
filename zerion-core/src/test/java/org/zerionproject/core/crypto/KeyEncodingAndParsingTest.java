package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.test.BrambleTestCase;
import org.zerionproject.core.test.TestSecureRandomProvider;
import org.junit.Test;

import java.security.GeneralSecurityException;

import static org.zerionproject.core.api.crypto.CryptoConstants.MAX_AGREEMENT_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.api.crypto.CryptoConstants.MAX_SIGNATURE_BYTES;
import static org.zerionproject.core.api.crypto.CryptoConstants.MAX_SIGNATURE_PUBLIC_KEY_BYTES;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

public class KeyEncodingAndParsingTest extends BrambleTestCase {

	private final CryptoComponentImpl crypto =
			new CryptoComponentImpl(new TestSecureRandomProvider(), null);

	@Test
	public void testAgreementPublicKeyLength() {

		for (int i = 0; i < 10; i++) {
			KeyPair keyPair = crypto.generateAgreementKeyPair();

			byte[] publicKey = keyPair.getPublic().getEncoded();
			assertTrue(publicKey.length <= MAX_AGREEMENT_PUBLIC_KEY_BYTES);
		}
	}

	@Test
	public void testAgreementPublicKeyEncodingAndParsing() throws Exception {
		KeyParser parser = crypto.getAgreementKeyParser();

		KeyPair aPair = crypto.generateAgreementKeyPair();
		KeyPair bPair = crypto.generateAgreementKeyPair();

		PublicKey aPub = aPair.getPublic();
		byte[] secret = crypto.performRawKeyAgreement(bPair.getPrivate(), aPub);

		aPub = parser.parsePublicKey(aPub.getEncoded());
		aPub = parser.parsePublicKey(aPub.getEncoded());

		byte[] secret1 =
				crypto.performRawKeyAgreement(bPair.getPrivate(), aPub);
		assertArrayEquals(secret, secret1);
	}

	@Test
	public void testAgreementPrivateKeyEncodingAndParsing() throws Exception {
		KeyParser parser = crypto.getAgreementKeyParser();

		KeyPair aPair = crypto.generateAgreementKeyPair();
		KeyPair bPair = crypto.generateAgreementKeyPair();

		PrivateKey bPriv = bPair.getPrivate();
		byte[] secret = crypto.performRawKeyAgreement(bPriv, aPair.getPublic());

		bPriv = parser.parsePrivateKey(bPriv.getEncoded());
		bPriv = parser.parsePrivateKey(bPriv.getEncoded());

		byte[] secret1 =
				crypto.performRawKeyAgreement(bPriv, aPair.getPublic());
		assertArrayEquals(secret, secret1);
	}

	@Test
	public void testAgreementKeyParserByFuzzing() {
		KeyParser parser = crypto.getAgreementKeyParser();

		KeyPair p = crypto.generateAgreementKeyPair();
		int pubLength = p.getPublic().getEncoded().length;
		int privLength = p.getPrivate().getEncoded().length;

		for (int i = 0; i < 1000; i++) {
			try {
				parser.parsePublicKey(getRandomBytes(pubLength));
			} catch (GeneralSecurityException expected) {

			}
			try {
				parser.parsePrivateKey(getRandomBytes(privLength));
			} catch (GeneralSecurityException expected) {

			}
		}
	}

	@Test
	public void testSignaturePublicKeyLength() {

		for (int i = 0; i < 10; i++) {
			KeyPair keyPair = crypto.generateSignatureKeyPair();

			byte[] publicKey = keyPair.getPublic().getEncoded();
			assertTrue(publicKey.length <= MAX_SIGNATURE_PUBLIC_KEY_BYTES);
		}
	}

	@Test
	public void testSignatureLength() throws Exception {

		for (int i = 0; i < 10; i++) {
			KeyPair keyPair = crypto.generateSignatureKeyPair();
			PrivateKey privateKey = keyPair.getPrivate();

			byte[] toBeSigned = getRandomBytes(1234);
			byte[] signature = crypto.sign("label", toBeSigned, privateKey);
			assertTrue(signature.length <= MAX_SIGNATURE_BYTES);
		}
	}

	@Test
	public void testSignaturePublicKeyEncodingAndParsing() throws Exception {
		KeyParser parser = crypto.getSignatureKeyParser();

		KeyPair keyPair = crypto.generateSignatureKeyPair();
		PublicKey publicKey = keyPair.getPublic();
		PrivateKey privateKey = keyPair.getPrivate();
		byte[] message = getRandomBytes(123);
		byte[] signature = crypto.sign("test", message, privateKey);

		assertTrue(crypto.verifySignature(signature, "test", message,
				publicKey));

		publicKey = parser.parsePublicKey(publicKey.getEncoded());

		assertTrue(crypto.verifySignature(signature, "test", message,
				publicKey));
	}

	@Test
	public void testSignaturePrivateKeyEncodingAndParsing() throws Exception {
		KeyParser parser = crypto.getSignatureKeyParser();

		KeyPair keyPair = crypto.generateSignatureKeyPair();
		PublicKey publicKey = keyPair.getPublic();
		PrivateKey privateKey = keyPair.getPrivate();
		byte[] message = getRandomBytes(123);
		byte[] signature = crypto.sign("test", message, privateKey);

		assertTrue(crypto.verifySignature(signature, "test", message,
				publicKey));

		privateKey = parser.parsePrivateKey(privateKey.getEncoded());

		byte[] signature1 = crypto.sign("test", message, privateKey);
		assertTrue(crypto.verifySignature(signature1, "test", message,
				publicKey));
		assertArrayEquals(signature, signature1);
	}

	@Test
	public void testSignatureKeyParserByFuzzing() {
		KeyParser parser = crypto.getSignatureKeyParser();

		KeyPair p = crypto.generateSignatureKeyPair();
		int pubLength = p.getPublic().getEncoded().length;
		int privLength = p.getPrivate().getEncoded().length;

		for (int i = 0; i < 1000; i++) {
			try {
				parser.parsePublicKey(getRandomBytes(pubLength));
			} catch (GeneralSecurityException expected) {

			}
			try {
				parser.parsePrivateKey(getRandomBytes(privLength));
			} catch (GeneralSecurityException expected) {

			}
		}
	}
}
