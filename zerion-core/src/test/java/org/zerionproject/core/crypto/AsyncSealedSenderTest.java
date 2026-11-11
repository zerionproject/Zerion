package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.crypto.async.AsyncEnvelope;
import org.zerionproject.core.crypto.async.AsyncSealedSender;
import org.zerionproject.core.system.SystemClock;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AsyncSealedSenderTest {

	private final Random random = new Random(2);
	private CryptoComponent crypto;
	private AsyncSealedSender sealer;

	private KeyPair rIdSig;
	private KeyPair rIdAgree;
	private KeyPair rPrekey;
	private KeyPair sIdSig;

	@Before
	public void setUp() {
		crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		sealer = new AsyncSealedSender(crypto);
		rIdSig = crypto.generateHybridSignatureKeyPair();
		rIdAgree = crypto.generateHybridAgreementKeyPair();
		rPrekey = crypto.generateHybridAgreementKeyPair();
		sIdSig = crypto.generateHybridSignatureKeyPair();
	}

	private AsyncSealedSender.SealRequest sealRequest(byte[] payload) {
		AsyncSealedSender.SealRequest r = new AsyncSealedSender.SealRequest();
		r.recipientAgreementPub = rPrekey.getPublic();
		r.prekeyKind = AsyncEnvelope.PREKEY_KIND_ONE_TIME;
		r.prekeyId = bytes(AsyncEnvelope.PREKEY_ID_BYTES);
		r.signedPrekeyId = 7L;
		r.recipientIdentitySigPub = rIdSig.getPublic().getEncoded();
		r.recipientIdentityAgreePub = rIdAgree.getPublic().getEncoded();
		r.senderIdentitySigPub = sIdSig.getPublic().getEncoded();
		r.senderIdentitySigPrivateKey = sIdSig.getPrivate();
		r.messageType = 5;
		r.payload = payload;
		r.ttl = 3600L;
		r.dedupId = bytes(AsyncEnvelope.DEDUP_ID_BYTES);
		r.sendTimestamp = 123456789L;
		return r;
	}

	private AsyncSealedSender.OpenRequest openRequest() {
		AsyncSealedSender.OpenRequest o = new AsyncSealedSender.OpenRequest();
		o.recipientAgreementKeyPair = rPrekey;
		o.recipientIdentitySigPub = rIdSig.getPublic().getEncoded();
		o.recipientIdentityAgreePub = rIdAgree.getPublic().getEncoded();
		return o;
	}

	private AsyncSealedSender.OpenRequest openRequest(byte[] linkKey) {
		AsyncSealedSender.OpenRequest o = openRequest();
		byte[] senderPub = sIdSig.getPublic().getEncoded();
		o.senderLinkKeys = peer -> java.util.Arrays.equals(peer, senderPub)
				? linkKey : null;
		return o;
	}

	private AsyncSealedSender.SealRequest keyedRequest(byte[] payload,
			byte[] linkKey) {
		AsyncSealedSender.SealRequest r = sealRequest(payload);
		r.senderLinkKey = linkKey;
		return r;
	}

	private byte[] bytes(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}

	@Test
	public void roundTripRecoversPayloadSenderAndTimestamp() throws Exception {
		byte[] payload = "hello over the mesh".getBytes();
		byte[] envelope = sealer.seal(sealRequest(payload));
		AsyncSealedSender.OpenedMessage m =
				sealer.open(envelope, openRequest());
		assertArrayEquals(payload, m.getPayload());
		assertEquals(5, m.getMessageType());
		assertEquals(123456789L, m.getSendTimestamp());
		assertArrayEquals(sIdSig.getPublic().getEncoded(),
				m.getSenderIdentitySigPub());
	}

	@Test
	public void emptyPayloadRoundTrips() throws Exception {
		byte[] envelope = sealer.seal(sealRequest(new byte[0]));
		AsyncSealedSender.OpenedMessage m =
				sealer.open(envelope, openRequest());
		assertEquals(0, m.getPayload().length);
	}

	@Test
	public void tamperingWithAeadBlobFails() throws Exception {
		byte[] envelope = sealer.seal(sealRequest("x".getBytes()));
		envelope[envelope.length - 1] ^= 0x01;
		expectFailure(envelope);
	}

	@Test
	public void tamperingWithKemCiphertextFails() throws Exception {
		byte[] envelope = sealer.seal(sealRequest("x".getBytes()));
		envelope[AsyncEnvelope.OFF_KEM_CIPHERTEXT] ^= 0x01;
		expectFailure(envelope);
	}

	@Test
	public void tamperingWithTtlFails() throws Exception {
		byte[] envelope = sealer.seal(sealRequest("x".getBytes()));
		envelope[AsyncEnvelope.OFF_TTL] ^= 0x01;
		expectFailure(envelope);
	}

	@Test
	public void tamperingWithEphemeralFails() throws Exception {
		byte[] envelope = sealer.seal(sealRequest("x".getBytes()));
		envelope[AsyncEnvelope.OFF_EPHEMERAL_PUB] ^= 0x01;
		expectFailure(envelope);
	}

	@Test
	public void wrongRecipientPrekeyFails() throws Exception {
		byte[] envelope = sealer.seal(sealRequest("x".getBytes()));
		AsyncSealedSender.OpenRequest o = openRequest();
		o.recipientAgreementKeyPair = crypto.generateHybridAgreementKeyPair();
		try {
			sealer.open(envelope, o);
			fail("expected failure");
		} catch (Exception expected) {
		}
	}

	@Test
	public void wrongRecipientIdentityFails() throws Exception {
		byte[] envelope = sealer.seal(sealRequest("x".getBytes()));
		AsyncSealedSender.OpenRequest o = openRequest();
		o.recipientIdentitySigPub =
				crypto.generateHybridSignatureKeyPair().getPublic()
						.getEncoded();
		try {
			sealer.open(envelope, o);
			fail("expected failure");
		} catch (Exception expected) {
		}
	}

	@Test
	public void keyedRecordOpensWithTheSharedLinkKey() throws Exception {
		byte[] linkKey = bytes(AsyncSealedSender.LINK_KEY_BYTES);
		byte[] payload = "keyed hello".getBytes();
		byte[] envelope = sealer.seal(keyedRequest(payload, linkKey));
		AsyncSealedSender.OpenedMessage m =
				sealer.open(envelope, openRequest(linkKey));
		assertArrayEquals(payload, m.getPayload());
		assertEquals(5, m.getMessageType());
		assertEquals(123456789L, m.getSendTimestamp());
		assertArrayEquals(sIdSig.getPublic().getEncoded(),
				m.getSenderIdentitySigPub());
		assertTrue(m.isKeyed());
	}

	@Test
	public void keyedRecordFailsWithAnotherLinkKey() throws Exception {
		byte[] linkKey = bytes(AsyncSealedSender.LINK_KEY_BYTES);
		byte[] envelope = sealer.seal(keyedRequest("x".getBytes(), linkKey));
		expectFailure(envelope,
				openRequest(bytes(AsyncSealedSender.LINK_KEY_BYTES)));
	}

	@Test
	public void keyedRecordFailsWithoutALinkKeyForTheSender()
			throws Exception {
		byte[] linkKey = bytes(AsyncSealedSender.LINK_KEY_BYTES);
		byte[] envelope = sealer.seal(keyedRequest("x".getBytes(), linkKey));
		expectFailure(envelope, openRequest());
		AsyncSealedSender.OpenRequest none = openRequest();
		none.senderLinkKeys = peer -> null;
		expectFailure(envelope, none);
	}

	@Test
	public void keyedRecordIsNeverAcceptedAsASignedOne() throws Exception {
		byte[] linkKey = bytes(AsyncSealedSender.LINK_KEY_BYTES);
		byte[] envelope = sealer.seal(keyedRequest("x".getBytes(), linkKey));
		expectFailure(envelope, openRequest());
	}

	@Test
	public void signedRecordIsNeverAcceptedAsAKeyedOne() throws Exception {
		byte[] linkKey = bytes(AsyncSealedSender.LINK_KEY_BYTES);
		byte[] envelope = sealer.seal(sealRequest("x".getBytes()));
		AsyncSealedSender.OpenedMessage m =
				sealer.open(envelope, openRequest(linkKey));
		assertFalse(m.isKeyed());
		AsyncSealedSender.OpenRequest refusing = openRequest();
		refusing.senderLinkKeys = peer -> {
			throw new AssertionError("a signed record asks for no link key");
		};
		assertFalse(sealer.open(envelope, refusing).isKeyed());
	}

	@Test
	public void signedRecordStillOpensWhenLinkKeysAreHeld()
			throws Exception {
		byte[] payload = "signed as before".getBytes();
		byte[] envelope = sealer.seal(sealRequest(payload));
		AsyncSealedSender.OpenedMessage m = sealer.open(envelope,
				openRequest(bytes(AsyncSealedSender.LINK_KEY_BYTES)));
		assertArrayEquals(payload, m.getPayload());
		assertFalse(m.isKeyed());
	}

	@Test
	public void keyedAndSignedEnvelopesHaveTheSameLength() throws Exception {
		byte[] payload = bytes(300);
		byte[] signed = sealer.seal(sealRequest(payload));
		byte[] keyed = sealer.seal(keyedRequest(payload,
				bytes(AsyncSealedSender.LINK_KEY_BYTES)));
		assertEquals(signed.length, keyed.length);
	}

	@Test
	public void tamperedKeyedPayloadFails() throws Exception {
		byte[] linkKey = bytes(AsyncSealedSender.LINK_KEY_BYTES);
		byte[] envelope = sealer.seal(keyedRequest("abc".getBytes(), linkKey));
		envelope[AsyncEnvelope.HEADER_BYTES + 16
				+ org.zerionproject.core.api.crypto.PostQuantumConstants
				.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES + 1] ^= 0x01;
		expectFailure(envelope, openRequest(linkKey));
		byte[] trailer = sealer.seal(keyedRequest("abc".getBytes(), linkKey));
		trailer[trailer.length - 1] ^= 0x01;
		expectFailure(trailer, openRequest(linkKey));
	}

	@Test
	public void keyedRecordIsBoundToTheRecipient() throws Exception {
		byte[] linkKey = bytes(AsyncSealedSender.LINK_KEY_BYTES);
		byte[] envelope = sealer.seal(keyedRequest("x".getBytes(), linkKey));
		AsyncSealedSender.OpenRequest o = openRequest(linkKey);
		o.recipientIdentitySigPub =
				crypto.generateHybridSignatureKeyPair().getPublic()
						.getEncoded();
		expectFailure(envelope, o);
	}

	private void expectFailure(byte[] envelope) {
		expectFailure(envelope, openRequest());
	}

	private void expectFailure(byte[] envelope,
			AsyncSealedSender.OpenRequest o) {
		try {
			sealer.open(envelope, o);
			fail("expected failure");
		} catch (Exception expected) {
		}
	}
}
