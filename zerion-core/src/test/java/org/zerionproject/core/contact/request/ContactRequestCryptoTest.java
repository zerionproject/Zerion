package org.zerionproject.core.contact.request;

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.junit.Test;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.contact.request.ContactRequestCrypto.Opened;
import org.zerionproject.core.contact.request.ContactRequestCrypto.Sealed;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.test.BrambleTestCase;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_LABEL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KIND_REQUEST;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.KIND_WITHDRAW;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.SEALED_BYTES;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;

public class ContactRequestCryptoTest extends BrambleTestCase {

	private final CryptoComponent crypto = PasswordCryptoForTests.create();
	private final ContactRequestCrypto c = new ContactRequestCrypto(crypto);
	private final KeyPair link = crypto.generateHybridAgreementKeyPair();
	private final byte[] linkId =
			crypto.hash(HYBRID_COMMITMENT_LABEL, link.getPublic().getEncoded());
	private final KeyPair inboxY = crypto.generateAgreementKeyPair();
	private final byte[] onion = getRandomBytes(32);
	private final byte[] nonce = getRandomBytes(32);
	private final byte[] keyPayload = ContactRequestRecords.encodeKey(
			link.getPublic().getEncoded(), inboxY.getPublic().getEncoded());
	private final byte[] helloHash = c.helloHash(7, 1, nonce, keyPayload);
	private final byte[] plaintext = getRandomBytes(1536);

	private Sealed seal() throws GeneralSecurityException {
		return c.seal(link.getPublic(), inboxY.getPublic().getEncoded(),
				helloHash, onion, linkId, plaintext);
	}

	@Test
	public void aSealedRequestOpensWithTheSameKeyAndPlaintext()
			throws Exception {
		Sealed sealed = seal();
		assertEquals(SEALED_BYTES, sealed.payload.length);
		Opened opened = c.open(link, inboxY, helloHash, onion, linkId,
				sealed.payload);
		assertArrayEquals(plaintext, opened.plaintext);
		byte[] requestId = getRandomBytes(16);
		assertArrayEquals(c.ack(sealed.mk, requestId, KIND_REQUEST),
				c.ack(opened.mk, requestId, KIND_REQUEST));
		assertFalse(Arrays.equals(c.ack(sealed.mk, requestId, KIND_REQUEST),
				c.ack(opened.mk, requestId, KIND_WITHDRAW)));
	}

	@Test
	public void everyBoundInputChangesTheKey() throws Exception {
		byte[] payload = seal().payload;
		byte[] otherKeyPayload = ContactRequestRecords.encodeKey(
				link.getPublic().getEncoded(),
				crypto.generateAgreementKeyPair().getPublic().getEncoded());
		byte[][] hellos = {
				c.helloHash(8, 1, nonce, keyPayload),
				c.helloHash(7, 2, nonce, keyPayload),
				c.helloHash(7, 1, getRandomBytes(32), keyPayload),
				c.helloHash(7, 1, nonce, otherKeyPayload)
		};
		for (byte[] h : hellos) {
			assertFails(link, inboxY, h, onion, linkId, payload);
		}
		assertFails(link, inboxY, helloHash, getRandomBytes(32), linkId,
				payload);
		assertFails(link, inboxY, helloHash, onion, getRandomBytes(32),
				payload);
		assertFails(link, crypto.generateAgreementKeyPair(), helloHash, onion,
				linkId, payload);
		assertFails(crypto.generateHybridAgreementKeyPair(), inboxY,
				helloHash, onion, linkId, payload);
		for (int i : new int[] {0, 31, 32, 1215, 1216, 2303, 2304, 2319,
				2320, SEALED_BYTES - 1}) {
			byte[] changed = payload.clone();
			changed[i] ^= 1;
			assertFails(link, inboxY, helloHash, onion, linkId, changed);
		}
	}

	@Test
	public void aRequestForOneInboxDoesNotOpenAtAnother() throws Exception {
		byte[] payload = seal().payload;
		byte[] otherNonce = getRandomBytes(32);
		byte[] otherHello = c.helloHash(7, 1, otherNonce, keyPayload);
		assertFails(link, inboxY, otherHello, getRandomBytes(32), linkId,
				payload);
	}

	@Test
	public void anAllZeroEphemeralAgreementIsRejectedOnBothSides()
			throws Exception {
		try {
			c.seal(link.getPublic(), new byte[32], helloHash, onion, linkId,
					plaintext);
			fail();
		} catch (GeneralSecurityException expected) {
		}
		byte[] payload = seal().payload;
		Arrays.fill(payload, 0, 32, (byte) 0);
		assertFails(link, inboxY, helloHash, onion, linkId, payload);
	}

	@Test
	public void theProofOfPossessionNeedsTheOneTimePrivateKey()
			throws Exception {
		KeyPair oneTime = crypto.generateHybridAgreementKeyPair();
		byte[] requestId = getRandomBytes(16);
		byte[] linkX = ((HybridAgreementPublicKey) link.getPublic())
				.getX25519PublicKey();
		byte[] pop = c.proofOfPossession(linkX, oneTime, nonce, requestId);
		byte[] oneTimeX = ((HybridAgreementPublicKey) oneTime.getPublic())
				.getX25519PublicKey();
		assertTrue(c.verifyProofOfPossession(pop, oneTimeX, link, nonce,
				requestId));
		byte[] borrowed = ((HybridAgreementPublicKey) crypto
				.generateHybridAgreementKeyPair().getPublic())
				.getX25519PublicKey();
		assertFalse(c.verifyProofOfPossession(pop, borrowed, link, nonce,
				requestId));
		assertFalse(c.verifyProofOfPossession(pop, oneTimeX, link,
				getRandomBytes(32), requestId));
		assertFalse(c.verifyProofOfPossession(pop, oneTimeX, link, nonce,
				getRandomBytes(16)));
		assertFalse(c.verifyProofOfPossession(pop, new byte[32], link, nonce,
				requestId));
		assertNotNull(oneTime.getPrivate().getEncoded());
		assertFalse(Arrays.equals(new byte[2432],
				oneTime.getPrivate().getEncoded()));
	}

	@Test
	public void theClientKeyIsAClampedX25519KeyBoundToLinkAndInbox() {
		byte[] body = getRandomBytes(65);
		byte[] priv = c.clientAuthPrivateKey(body, onion);
		assertEquals(0, priv[0] & 7);
		assertEquals(64, priv[31] & 0xC0);
		byte[] pub = c.clientAuthPublicKey(body, onion);
		assertArrayEquals(new X25519PrivateKeyParameters(priv, 0)
				.generatePublicKey().getEncoded(), pub);
		assertArrayEquals(priv, c.clientAuthPrivateKey(body, onion));
		assertFalse(Arrays.equals(priv,
				c.clientAuthPrivateKey(body, getRandomBytes(32))));
		body[5] ^= 1;
		assertFalse(Arrays.equals(priv, c.clientAuthPrivateKey(body, onion)));
	}

	@Test
	public void theSenderTagDependsOnAccountAndLink() {
		byte[] secret = getRandomBytes(32);
		byte[] tag = c.senderTag(secret, linkId);
		assertEquals(32, tag.length);
		assertArrayEquals(tag, c.senderTag(secret, linkId));
		assertFalse(Arrays.equals(tag, c.senderTag(secret, getRandomBytes(32))));
		assertFalse(Arrays.equals(tag,
				c.senderTag(getRandomBytes(32), linkId)));
	}

	@Test
	public void theFastSolverAgreesWithTheHashFunction() {
		Random random = new Random(1);
		for (int d : new int[] {0, 4, 12}) {
			byte[] x = c.solve(nonce, linkId, d, random, () -> false);
			assertNotNull(x);
			assertTrue(c.isSolution(nonce, linkId, x, d));
			assertTrue(ContactRequestCrypto.leadingZeroBits(
					c.puzzleHash(nonce, linkId, x)) >= d);
		}
		assertEquals(0, ContactRequestCrypto.leadingZeroBits(
				new byte[] {(byte) 0x80}));
		assertEquals(9, ContactRequestCrypto.leadingZeroBits(
				new byte[] {0, 0x40}));
		assertEquals(16, ContactRequestCrypto.leadingZeroBits(new byte[2]));
		assertFalse(c.isSolution(nonce, linkId, new byte[7], 0));
	}

	private void assertFails(KeyPair l, KeyPair y, byte[] h, byte[] o,
			byte[] id, byte[] payload) {
		try {
			c.open(l, y, h, o, id, payload);
			fail();
		} catch (GeneralSecurityException expected) {
		} catch (IllegalArgumentException expected) {
		}
	}
}
