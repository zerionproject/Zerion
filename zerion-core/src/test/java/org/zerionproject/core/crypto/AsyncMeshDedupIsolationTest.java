package org.zerionproject.core.crypto;

import org.zerionproject.core.api.FormatException;
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

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class AsyncMeshDedupIsolationTest {

	private final SecureRandom random = new SecureRandom();
	private CryptoComponent crypto;
	private final AtomicInteger opens = new AtomicInteger();
	private AsyncSealedSender sealer;

	private AsyncMeshDelivery.Identity alice;
	private AsyncMeshDelivery.Identity mallory;
	private AsyncMeshDelivery.Identity recipientId;
	private final AtomicInteger settingsReads = new AtomicInteger();
	private final InMemorySettingsManager settings =
			new InMemorySettingsManager() {
				@Override
				public synchronized org.zerionproject.core.api.settings
						.Settings getSettings(String namespace) {
					settingsReads.incrementAndGet();
					return super.getSettings(namespace);
				}
			};
	private AsyncPrekeyStore store;
	private AsyncPrekeyBundle bundle;
	private final Set<String> known = new HashSet<>();
	private final List<byte[]> openedSenders =
			Collections.synchronizedList(new ArrayList<>());
	private final List<byte[]> openedPayloads =
			Collections.synchronizedList(new ArrayList<>());

	@Before
	public void setUp() throws Exception {
		crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		sealer = new AsyncSealedSender(crypto) {
			@Override
			public OpenedMessage open(byte[] envelopeBytes, OpenRequest r)
					throws GeneralSecurityException, FormatException {
				opens.incrementAndGet();
				return super.open(envelopeBytes, r);
			}
		};
		alice = newIdentity();
		mallory = newIdentity();
		recipientId = newIdentity();
		store = new AsyncPrekeyStore(crypto, settings, new SystemClock());
		AsyncPrekeyStore.SignedPrekey spk = store.getSignedPrekey();
		bundle = AsyncPrekeyBundle.create(crypto, recipientId.sigPub,
				recipientId.sigPriv, recipientId.agreePub, spk.id, spk.pub,
				spk.expiry, Collections.emptyList());
		known.add(Arrays.toString(alice.sigPub));
	}

	@Test
	public void aStrangerReusingTheDedupIdDoesNotSuppressTheContact()
			throws Exception {
		byte[] dedup = randomDedup();
		AsyncMeshDelivery d = delivery();
		d.onFrame(seal(mallory, "forged".getBytes(), dedup));
		d.onFrame(seal(alice, "genuine".getBytes(), dedup));

		assertEquals("the contact's envelope must still be delivered", 1,
				openedPayloads.size());
		assertArrayEquals("genuine".getBytes(), openedPayloads.get(0));
		assertArrayEquals(alice.sigPub, openedSenders.get(0));
	}

	@Test
	public void twoContactsWithTheSameDedupIdAreBothDelivered()
			throws Exception {
		known.add(Arrays.toString(mallory.sigPub));
		byte[] dedup = randomDedup();
		AsyncMeshDelivery d = delivery();
		d.onFrame(seal(mallory, "first".getBytes(), dedup));
		d.onFrame(seal(alice, "second".getBytes(), dedup));

		assertEquals("both senders delivered", 2, openedPayloads.size());
	}

	@Test
	public void aCollisionStoredBeforeARestartDoesNotSuppressTheContact()
			throws Exception {
		known.add(Arrays.toString(mallory.sigPub));
		byte[] dedup = randomDedup();
		delivery().onFrame(seal(mallory, "first".getBytes(), dedup));
		assertEquals(1, openedPayloads.size());

		delivery().onFrame(seal(alice, "after restart".getBytes(), dedup));

		assertEquals("the persistent fast path must not match another"
				+ " sender's envelope", 2, openedPayloads.size());
	}

	@Test
	public void aReplayOfTheSameEnvelopeIsStillDropped() throws Exception {
		byte[] envelope = seal(alice, "once".getBytes(), randomDedup());
		AsyncMeshDelivery d = delivery();
		for (int i = 0; i < 20; i++) d.onFrame(envelope);
		assertEquals(1, openedPayloads.size());
		assertEquals("replays in memory cost no further opens", 1,
				opens.get());

		AsyncMeshDelivery restarted = delivery();
		for (int i = 0; i < 20; i++) restarted.onFrame(envelope);
		assertEquals("a replay after a restart is not delivered", 1,
				openedPayloads.size());
		assertEquals("a replay after a restart is refused before opening", 1,
				opens.get());
	}

	@Test
	public void aStrangersReplayIsNotReopened() throws Exception {
		byte[] envelope = seal(mallory, "stranger".getBytes(), randomDedup());
		AsyncMeshDelivery d = delivery();
		for (int i = 0; i < 20; i++) d.onFrame(envelope);
		assertEquals(0, openedPayloads.size());
		assertEquals(1, opens.get());
	}

	@Test
	public void anExpiredEnvelopeIsOpenedOnlyOnce() throws Exception {
		byte[] envelope = seal(alice, "late".getBytes(), randomDedup());
		long afterExpiry = System.currentTimeMillis() + 3601_000L;
		AsyncMeshDelivery d = delivery(store, new FixedClock(afterExpiry));
		for (int i = 0; i < 20; i++) d.onFrame(envelope);
		assertEquals(0, openedPayloads.size());
		assertEquals("replays of an expired envelope were opened again", 1,
				opens.get());
	}

	@Test
	public void aReplayIsRefusedBeforeOpeningByAFreshStore()
			throws Exception {
		byte[] envelope = seal(alice, "once".getBytes(), randomDedup());
		delivery().onFrame(envelope);
		assertEquals(1, opens.get());

		AsyncPrekeyStore reloaded =
				new AsyncPrekeyStore(crypto, settings, new SystemClock());
		AsyncMeshDelivery restarted =
				delivery(reloaded, new SystemClock());
		for (int i = 0; i < 5; i++) restarted.onFrame(envelope);
		assertEquals(1, openedPayloads.size());
		assertEquals("a replay after a restart was opened", 1, opens.get());
	}

	@Test
	public void theOpenedSetIsNotReadFromStorageForEveryFrame()
			throws Exception {
		store.isEnvelopeOpened(new byte[16]);
		settingsReads.set(0);
		byte[] digest = new byte[16];
		for (int i = 0; i < 100; i++) {
			random.nextBytes(digest);
			store.isEnvelopeOpened(digest);
		}
		assertEquals("settings reads for 100 lookups", 0,
				settingsReads.get());
	}

	private AsyncMeshDelivery delivery() {
		return delivery(store, new SystemClock());
	}

	private AsyncMeshDelivery delivery(AsyncPrekeyStore s,
			org.zerionproject.core.api.system.Clock clock) {
		return new AsyncMeshDelivery(crypto, sealer, s,
				new AsyncMeshDelivery.OpenedListener() {
					@Override
					public boolean onOpened(byte[] senderPub, int type,
							byte[] payload, long ts) {
						openedSenders.add(senderPub);
						return openedPayloads.add(payload);
					}

					@Override
					public boolean knowsSender(byte[] senderPub) {
						return known.contains(Arrays.toString(senderPub));
					}
				}, recipientId, clock);
	}

	private static final class FixedClock
			implements org.zerionproject.core.api.system.Clock {
		private final long now;

		FixedClock(long now) {
			this.now = now;
		}

		@Override
		public long currentTimeMillis() {
			return now;
		}

		@Override
		public void sleep(long milliseconds) {
		}
	}

	private byte[] seal(AsyncMeshDelivery.Identity from, byte[] payload,
			byte[] dedup) throws Exception {
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
		r.payload = payload;
		r.ttl = 3600L;
		r.dedupId = dedup.clone();
		r.sendTimestamp = System.currentTimeMillis();
		return new AsyncSealedSender(crypto).seal(r);
	}

	private byte[] randomDedup() {
		byte[] d = new byte[AsyncEnvelope.DEDUP_ID_BYTES];
		random.nextBytes(d);
		return d;
	}

	private AsyncMeshDelivery.Identity newIdentity() {
		KeyPair sig = crypto.generateHybridSignatureKeyPair();
		KeyPair agree = crypto.generateHybridAgreementKeyPair();
		return new AsyncMeshDelivery.Identity(sig.getPublic().getEncoded(),
				sig.getPrivate(), agree.getPublic().getEncoded());
	}
}
