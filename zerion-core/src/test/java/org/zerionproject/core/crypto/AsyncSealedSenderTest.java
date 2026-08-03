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
			// ok
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
			// ok
		}
	}

	private void expectFailure(byte[] envelope) {
		try {
			sealer.open(envelope, openRequest());
			fail("expected failure");
		} catch (Exception expected) {
			// ok
		}
	}
}
