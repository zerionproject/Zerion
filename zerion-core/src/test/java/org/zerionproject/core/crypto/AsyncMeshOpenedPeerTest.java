package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.crypto.async.AsyncEnvelope;
import org.zerionproject.core.crypto.async.AsyncMeshDelivery;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.AsyncPrekeyStore;
import org.zerionproject.core.crypto.async.AsyncSealedSender;
import org.zerionproject.core.system.SystemClock;
import org.junit.Before;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class AsyncMeshOpenedPeerTest {

	private final SecureRandom random = new SecureRandom();
	private CryptoComponent crypto;
	private AsyncSealedSender sealer;
	private AsyncMeshDelivery.Identity contact;
	private AsyncMeshDelivery.Identity stranger;
	private AsyncMeshDelivery.Identity recipient;
	private AsyncPrekeyBundle bundle;
	private AsyncPrekeyStore store;
	private AsyncMeshDelivery delivery;
	private final List<String> credited =
			Collections.synchronizedList(new ArrayList<>());

	@Before
	public void setUp() throws Exception {
		crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		sealer = new AsyncSealedSender(crypto);
		contact = newIdentity();
		stranger = newIdentity();
		recipient = newIdentity();
		store = new AsyncPrekeyStore(crypto,
				new InMemorySettingsManager(), new SystemClock());
		AsyncPrekeyStore.SignedPrekey spk = store.getSignedPrekey();
		bundle = AsyncPrekeyBundle.create(crypto, recipient.sigPub,
				recipient.sigPriv, recipient.agreePub, spk.id, spk.pub,
				spk.expiry, Collections.emptyList());
		delivery = newDelivery();
	}

	private AsyncMeshDelivery newDelivery() {
		AsyncMeshDelivery d = new AsyncMeshDelivery(crypto, sealer, store,
				new AsyncMeshDelivery.OpenedListener() {
					@Override
					public boolean onOpened(byte[] s, int t, byte[] p,
							long ts) {
						return true;
					}

					@Override
					public boolean knowsSender(byte[] s) {
						return Arrays.equals(s, contact.sigPub);
					}
				}, recipient, new SystemClock());
		d.setOpenedFromPeerListener(credited::add);
		return d;
	}

	@Test
	public void aContactsSecondEnvelopeUnderTheSameDedupIdEarnsNoCredit()
			throws Exception {
		byte[] dedup = new byte[AsyncEnvelope.DEDUP_ID_BYTES];
		random.nextBytes(dedup);
		delivery.onFrame(seal(contact, dedup), "s:first");
		newDelivery().onFrame(seal(contact, dedup), "s:second");
		assertEquals("the seen set refused it, so no credit",
				Collections.singletonList("s:first"), credited);
	}

	@Test
	public void onlyAFreshEnvelopeFromAContactCreditsTheNeighbour()
			throws Exception {
		byte[] genuine = seal(contact);
		delivery.onFrame(genuine, "s:genuine");
		assertEquals(Collections.singletonList("s:genuine"), credited);

		delivery.onFrame(genuine, "s:replayer");
		delivery.onFrame(seal(stranger), "s:stranger");
		delivery.onFrame(forgery(), "s:forger");

		assertEquals("replays, strangers and forgeries earn no credit",
				Collections.singletonList("s:genuine"), credited);
	}

	private byte[] seal(AsyncMeshDelivery.Identity from) throws Exception {
		byte[] dedup = new byte[AsyncEnvelope.DEDUP_ID_BYTES];
		random.nextBytes(dedup);
		return seal(from, dedup);
	}

	private byte[] seal(AsyncMeshDelivery.Identity from, byte[] dedup)
			throws Exception {
		AsyncSealedSender.SealRequest r = new AsyncSealedSender.SealRequest();
		r.prekeyKind = AsyncEnvelope.PREKEY_KIND_SIGNED;
		r.prekeyId = new byte[AsyncEnvelope.PREKEY_ID_BYTES];
		r.recipientAgreementPub = crypto.getHybridAgreementKeyParser()
				.parsePublicKey(bundle.getSignedPrekeyPub());
		r.signedPrekeyId = bundle.getSignedPrekeyId();
		r.recipientIdentitySigPub = bundle.getIdentitySigPub();
		r.recipientIdentityAgreePub = bundle.getIdentityAgreePub();
		r.senderIdentitySigPub = from.sigPub;
		r.senderIdentitySigPrivateKey = from.sigPriv;
		r.messageType = 9;
		r.payload = "hello".getBytes();
		r.ttl = 3600L;
		r.dedupId = dedup.clone();
		r.sendTimestamp = System.currentTimeMillis();
		return sealer.seal(r);
	}

	private byte[] forgery() throws Exception {
		byte[] sealed = seal(contact);
		sealed[sealed.length - 1] ^= 0x01;
		return sealed;
	}

	private AsyncMeshDelivery.Identity newIdentity() {
		KeyPair sig = crypto.generateHybridSignatureKeyPair();
		KeyPair agree = crypto.generateHybridAgreementKeyPair();
		return new AsyncMeshDelivery.Identity(sig.getPublic().getEncoded(),
				sig.getPrivate(), agree.getPublic().getEncoded());
	}
}
