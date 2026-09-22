package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.crypto.async.AsyncEnvelope;
import org.zerionproject.core.crypto.async.AsyncMeshDelivery;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.AsyncPrekeyStore;
import org.zerionproject.core.crypto.async.AsyncSealedSender;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.transport.mesh.MeshForwarder;
import org.zerionproject.transport.mesh.MeshLink;
import org.junit.Before;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * End-to-end proof of the Phase 2 + Phase 3 software pipeline with no radio:
 * the recipient generates prekeys and publishes a signed bundle, the sender
 * seals a message to it and floods it across the mesh, and the recipient opens
 * it and consumes the one-time prekey. Relays cannot open it.
 */
public class AsyncMeshIntegrationTest {

	private final SecureRandom random = new SecureRandom();
	private CryptoComponent crypto;
	private AsyncSealedSender sealer;

	@Before
	public void setUp() {
		crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		sealer = new AsyncSealedSender(crypto);
	}

	private AsyncMeshDelivery.Identity newIdentity() {
		KeyPair sig = crypto.generateHybridSignatureKeyPair();
		KeyPair agree = crypto.generateHybridAgreementKeyPair();
		return new AsyncMeshDelivery.Identity(sig.getPublic().getEncoded(),
				sig.getPrivate(), agree.getPublic().getEncoded());
	}

	private void connect(MeshForwarder a, String aId, MeshForwarder b,
			String bId) {
		a.addLink(link(aId, b, bId));
		b.addLink(link(bId, a, aId));
	}

	private MeshLink link(String id, MeshForwarder target, String inbound) {
		return new MeshLink() {
			@Override
			public String getId() {
				return id;
			}

			@Override
			public void broadcast(byte[] frame) {
				target.onReceive(frame, inbound);
			}
		};
	}

	@Test
	public void sealFloodOpenAndConsume() throws Exception {
		// Recipient R: identity, prekey store, published bundle.
		AsyncMeshDelivery.Identity rId = newIdentity();
		AsyncPrekeyStore rStore = new AsyncPrekeyStore(crypto,
				new InMemorySettingsManager(), new SystemClock());
		AsyncMeshDelivery.Identity rSigningIdentity = rId;
		List<AsyncPrekeyBundle.OneTimePrekey> otks =
				rStore.generateOneTimePrekeys(5);
		AsyncPrekeyStore.SignedPrekey spk = rStore.getSignedPrekey();
		AsyncPrekeyBundle bundle = AsyncPrekeyBundle.create(crypto,
				rSigningIdentity.sigPub, rSigningIdentity.sigPriv,
				rSigningIdentity.agreePub, spk.id, spk.pub, spk.expiry, otks);
		assertTrue(bundle.verify(crypto));

		List<byte[]> rOpened = new ArrayList<>();
		AsyncMeshDelivery rDelivery = new AsyncMeshDelivery(crypto, sealer,
				rStore, (senderPub, type, payload, ts) -> rOpened.add(payload),
				rId, new SystemClock());
		MeshForwarder rForwarder = new MeshForwarder(rDelivery, random);

		// Sender S: identity, its own store and delivery (as a mesh node).
		AsyncMeshDelivery.Identity sId = newIdentity();
		AsyncPrekeyStore sStore = new AsyncPrekeyStore(crypto,
				new InMemorySettingsManager(), new SystemClock());
		AsyncMeshDelivery sDelivery = new AsyncMeshDelivery(crypto, sealer,
				sStore, (senderPub, type, payload, ts) -> true, sId,
				new SystemClock());
		MeshForwarder sForwarder = new MeshForwarder(sDelivery, random);

		// A relay in the middle that cannot open anything.
		AsyncMeshDelivery.Identity relayId = newIdentity();
		AsyncPrekeyStore relayStore = new AsyncPrekeyStore(crypto,
				new InMemorySettingsManager(), new SystemClock());
		List<byte[]> relayOpened = new ArrayList<>();
		AsyncMeshDelivery relayDelivery = new AsyncMeshDelivery(crypto, sealer,
				relayStore,
				(senderPub, type, payload, ts) -> relayOpened.add(payload),
				relayId, new SystemClock());
		MeshForwarder relayForwarder =
				new MeshForwarder(relayDelivery, random);

		connect(sForwarder, "s-r", relayForwarder, "r-s");
		connect(relayForwarder, "r-x", rForwarder, "x-r");

		byte[] payload = "offline mesh hello".getBytes();
		sDelivery.send(sForwarder, bundle, 9, payload, 3600L,
				System.currentTimeMillis(), true);

		assertEquals(1, rOpened.size());
		assertArrayEquals(payload, rOpened.get(0));
		assertEquals(0, relayOpened.size());
	}

	/**
	 * PROTO-09: replaying one captured valid envelope under fresh frame ids
	 * must open (decapsulate and verify) it at most once. The dedup id is
	 * checked before the open, so repeats cost a settings read, not crypto.
	 */
	@Test
	public void replayedEnvelopeIsOpenedOnlyOnce() throws Exception {
		Recipient r = new Recipient(new SystemClock());
		byte[] envelope = sealTo(r, "replay me".getBytes(), 3600L,
				System.currentTimeMillis());

		for (int i = 0; i < 1000; i++) r.delivery.onFrame(envelope);

		assertEquals(1, r.opened.size());
	}

	/**
	 * PROTO-09: an envelope whose time-to-live has elapsed against the clock
	 * is rejected rather than delivered, closing the unbounded replay window.
	 */
	@Test
	public void expiredEnvelopeIsRejected() throws Exception {
		long base = System.currentTimeMillis();
		MutableClock clock = new MutableClock(base);
		Recipient r = new Recipient(clock);
		byte[] envelope = sealTo(r, "too late".getBytes(), 1L, base);

		clock.now = base + 5_000L;
		r.delivery.onFrame(envelope);

		assertEquals(0, r.opened.size());
	}

	/**
	 * CRY-08: an envelope sealed to the reusable signed prekey must stay
	 * rejected after the seen-set has been filled past its bound by newer
	 * envelopes. Eviction raises the floor to the evicted expiry, so the
	 * old envelope is refused whether or not it is still remembered.
	 */
	@Test
	public void replayAfterSeenSetEvictionIsStillRejected() throws Exception {
		Recipient r = new Recipient(new SystemClock(), 4);
		long now = System.currentTimeMillis();
		byte[] old = sealTo(r, "old".getBytes(), 600L, now, false);
		r.delivery.onFrame(old);
		assertEquals(1, r.opened.size());

		for (int i = 0; i < 6; i++) {
			r.delivery.onFrame(sealTo(r, ("newer " + i).getBytes(),
					3600L + i, now, false));
		}
		assertEquals(7, r.opened.size());
		assertFalse("the old envelope must have been evicted",
				r.store.isSeen(AsyncEnvelope.decode(old).getDedupId()));

		r.delivery.onFrame(old);
		assertEquals("evicted envelope replayed", 7, r.opened.size());
	}

	/**
	 * CRY-08: the floor refuses only what expires at or before the oldest
	 * evicted entry; a fresh envelope expiring later still opens.
	 */
	@Test
	public void envelopesAboveTheFloorStillOpenAfterEviction()
			throws Exception {
		Recipient r = new Recipient(new SystemClock(), 2);
		long now = System.currentTimeMillis();
		r.delivery.onFrame(sealTo(r, "a".getBytes(), 100L, now, false));
		r.delivery.onFrame(sealTo(r, "b".getBytes(), 200L, now, false));
		r.delivery.onFrame(sealTo(r, "c".getBytes(), 300L, now, false));
		assertEquals(3, r.opened.size());
		assertEquals(now + 100_000L, r.store.seenFloor());

		r.delivery.onFrame(sealTo(r, "too old".getBytes(), 100L, now, false));
		assertEquals("at the floor: refused unseen", 3, r.opened.size());
		r.delivery.onFrame(sealTo(r, "later".getBytes(), 400L, now, false));
		assertEquals(4, r.opened.size());
	}

	/** Seals a message to {@code r} and returns the raw envelope bytes. */
	private byte[] sealTo(Recipient r, byte[] payload, long ttlSeconds,
			long sendTimestamp) throws Exception {
		return sealTo(r, payload, ttlSeconds, sendTimestamp, true);
	}

	private byte[] sealTo(Recipient r, byte[] payload, long ttlSeconds,
			long sendTimestamp, boolean preferOneTime) throws Exception {
		AsyncMeshDelivery.Identity sId = newIdentity();
		AsyncPrekeyStore sStore = new AsyncPrekeyStore(crypto,
				new InMemorySettingsManager(), new SystemClock());
		AsyncMeshDelivery sDelivery = new AsyncMeshDelivery(crypto, sealer,
				sStore, (a, b, c, d) -> true, sId, new SystemClock());
		MeshForwarder sForwarder = new MeshForwarder(sDelivery, random);
		List<byte[]> captured = new ArrayList<>();
		MeshForwarder relay = new MeshForwarder(captured::add, random);
		connect(sForwarder, "s-r", relay, "r-s");
		sDelivery.send(sForwarder, r.bundle, 9, payload, ttlSeconds,
				sendTimestamp, preferOneTime);
		return captured.get(0);
	}

	private final class Recipient {
		final AsyncPrekeyStore store;
		final AsyncPrekeyBundle bundle;
		final AsyncMeshDelivery delivery;
		final List<byte[]> opened = new ArrayList<>();

		Recipient(org.zerionproject.core.api.system.Clock clock)
				throws Exception {
			this(clock, AsyncPrekeyStore.MAX_SEEN);
		}

		Recipient(org.zerionproject.core.api.system.Clock clock, int maxSeen)
				throws Exception {
			store = new AsyncPrekeyStore(crypto,
					new InMemorySettingsManager(), new SystemClock(), maxSeen);
			AsyncMeshDelivery.Identity id = newIdentity();
			List<AsyncPrekeyBundle.OneTimePrekey> otks =
					store.generateOneTimePrekeys(5);
			AsyncPrekeyStore.SignedPrekey spk = store.getSignedPrekey();
			bundle = AsyncPrekeyBundle.create(crypto, id.sigPub, id.sigPriv,
					id.agreePub, spk.id, spk.pub, spk.expiry, otks);
			delivery = new AsyncMeshDelivery(crypto, sealer, store,
					(senderPub, type, payload, ts) -> opened.add(payload),
					id, clock);
		}
	}

	private static final class MutableClock
			implements org.zerionproject.core.api.system.Clock {
		volatile long now;

		MutableClock(long now) {
			this.now = now;
		}

		@Override
		public long currentTimeMillis() {
			return now;
		}

		@Override
		public void sleep(long milliseconds) throws InterruptedException {
			Thread.sleep(milliseconds);
		}
	}
}
