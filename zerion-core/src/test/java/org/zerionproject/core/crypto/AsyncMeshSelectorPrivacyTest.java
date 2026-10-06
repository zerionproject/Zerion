package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.crypto.async.AsyncEnvelope;
import org.zerionproject.core.crypto.async.AsyncMeshDelivery;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.AsyncPrekeyStore;
import org.zerionproject.core.crypto.async.AsyncSealedSender;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.util.StringUtils;
import org.zerionproject.transport.mesh.MeshForwarder;
import org.zerionproject.transport.mesh.MeshLink;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class AsyncMeshSelectorPrivacyTest {

	private final SecureRandom random = new SecureRandom();
	private CryptoComponent crypto;
	private final AtomicInteger decapsulations = new AtomicInteger();
	private CryptoComponent counting;
	private AsyncMeshDelivery.Identity sender;

	@Before
	public void setUp() {
		crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		counting = (CryptoComponent) Proxy.newProxyInstance(
				CryptoComponent.class.getClassLoader(),
				new Class<?>[] {CryptoComponent.class},
				(proxy, method, args) -> {
					if (method.getName().equals("deriveHybridSharedSecret")) {
						decapsulations.incrementAndGet();
					}
					try {
						return method.invoke(crypto, args);
					} catch (InvocationTargetException e) {
						throw e.getCause();
					}
				});
		sender = identity(null);
	}

	@Test
	public void everyAccountPublishesTheSameSignedPrekeyId() throws Exception {
		Recipient young = new Recipient(new SystemClock());
		Recipient old = new Recipient(new SystemClock());
		for (int i = 0; i < 5; i++) old.store.rotateSignedPrekey();
		AsyncPrekeyBundle a = young.bundleFor(1);
		AsyncPrekeyBundle b = old.bundleFor(1);
		assertEquals(AsyncPrekeyStore.PUBLISHED_SIGNED_PREKEY_ID,
				a.getSignedPrekeyId());
		assertEquals(a.getSignedPrekeyId(), b.getSignedPrekeyId());
		assertEquals(AsyncPrekeyStore.PUBLISHED_SIGNED_PREKEY_ID,
				AsyncEnvelope.decode(seal(b, false)).getSignedPrekeyId());
		old.delivery.onFrame(seal(b, false));
		assertEquals(1, old.opened.size());
	}

	@Test
	public void eachContactKnowsTheOneTimePrekeysUnderItsOwnIds()
			throws Exception {
		Recipient r = new Recipient(new SystemClock());
		AsyncPrekeyBundle toA = r.bundleFor(1);
		AsyncPrekeyBundle toB = r.bundleFor(2);
		assertEquals(toA.getOneTimePrekeys().size(),
				toB.getOneTimePrekeys().size());
		Set<String> idsA = ids(toA);
		Set<String> idsB = ids(toB);
		Set<String> shared = new HashSet<>(idsA);
		shared.retainAll(idsB);
		assertTrue(shared.isEmpty());
		Set<String> stored = new HashSet<>();
		for (AsyncPrekeyBundle.OneTimePrekey p :
				r.store.topUpOneTimePrekeys(5)) {
			stored.add(StringUtils.toHexString(p.id));
		}
		Set<String> leaked = new HashSet<>(stored);
		leaked.retainAll(idsA);
		assertTrue(leaked.isEmpty());
		assertEquals(idsA, ids(r.bundleFor(1)));

		byte[] fromA = seal(toA, true);
		byte[] prekeyId = AsyncEnvelope.decode(fromA).getPrekeyId();
		assertTrue(idsA.contains(StringUtils.toHexString(prekeyId)));
		assertFalse(idsB.contains(StringUtils.toHexString(prekeyId)));
		r.delivery.onFrame(fromA);
		assertEquals(1, r.opened.size());
	}

	@Test
	public void aOneTimePrekeyUsedThroughOneContactIsGoneForAll()
			throws Exception {
		Recipient r = new Recipient(new SystemClock(), 1);
		AsyncPrekeyBundle toA = r.bundleFor(1);
		AsyncPrekeyBundle toB = r.bundleFor(2);
		r.delivery.onFrame(seal(toA, true));
		assertEquals(1, r.opened.size());
		assertEquals(0, r.store.topUpOneTimePrekeys(0).size());
		r.delivery.onFrame(seal(toB, true));
		assertEquals(1, r.opened.size());
	}

	@Test
	public void theBundleCarriesAKeyOfItsOwnNotTheLegacyKey()
			throws Exception {
		Recipient r = new Recipient(new SystemClock());
		AsyncPrekeyBundle bundle = r.bundleFor(1);
		assertArrayEquals(r.store.getMeshAgreementPublicKey(),
				bundle.getIdentityAgreePub());
		assertFalse(Arrays.equals(r.legacyAgreePub,
				bundle.getIdentityAgreePub()));
		assertArrayEquals(bundle.getIdentityAgreePub(),
				new AsyncPrekeyStore(crypto, r.settings, new SystemClock())
						.getMeshAgreementPublicKey());
	}

	@Test
	public void aBundleOfAnEarlierReleaseStillOpens() throws Exception {
		Recipient r = new Recipient(new SystemClock());
		List<AsyncPrekeyBundle.OneTimePrekey> real =
				r.store.topUpOneTimePrekeys(5);
		AsyncPrekeyStore.SignedPrekey spk = r.store.getSignedPrekey();
		assertNotEquals(AsyncPrekeyStore.PUBLISHED_SIGNED_PREKEY_ID, spk.id);
		AsyncPrekeyBundle legacy = AsyncPrekeyBundle.create(crypto,
				r.identity.sigPub, r.identity.sigPriv, r.legacyAgreePub,
				spk.id, spk.pub, spk.expiry, real);
		r.delivery.onFrame(seal(legacy, false));
		r.delivery.onFrame(seal(legacy, true));
		assertEquals(2, r.opened.size());
		assertEquals(4, r.store.topUpOneTimePrekeys(0).size());
	}

	@Test
	public void envelopesSealedBeforeARotationStillOpen() throws Exception {
		Recipient r = new Recipient(new SystemClock());
		AsyncPrekeyBundle before = r.bundleFor(1);
		r.store.rotateSignedPrekey();
		AsyncPrekeyBundle after = r.bundleFor(1);
		r.delivery.onFrame(seal(before, false));
		r.delivery.onFrame(seal(after, false));
		assertEquals(2, r.opened.size());
	}

	@Test
	public void thePreviousSignedPrekeyIsNotTriedOnceItsEnvelopesAreDead()
			throws Exception {
		MutableClock clock = new MutableClock(System.currentTimeMillis());
		Recipient r = new Recipient(clock);
		AsyncPrekeyStore.SignedPrekey first = r.store.getSignedPrekey();
		clock.now = first.expiry * 1000L + 100_000L;
		r.store.getSignedPrekey();
		decapsulations.set(0);
		r.delivery.onFrame(forgery(3600L));
		assertEquals("an hour-long envelope may be sealed to either key", 2,
				decapsulations.get());
		clock.now = first.expiry * 1000L + 185_000L;
		decapsulations.set(0);
		r.delivery.onFrame(forgery(180L));
		assertEquals("a three-minute envelope sealed to the previous key"
				+ " would be dead", 1, decapsulations.get());
	}

	private byte[] forgery(long ttl) {
		byte[] ephemeral = crypto.generateHybridAgreementKeyPair().getPublic()
				.getEncoded();
		byte[] kemCiphertext = new byte[org.zerionproject.core.api.crypto
				.PostQuantumConstants.ML_KEM_768_CIPHERTEXT_BYTES];
		random.nextBytes(kemCiphertext);
		byte[] dedup = new byte[AsyncEnvelope.DEDUP_ID_BYTES];
		random.nextBytes(dedup);
		byte[] blob = new byte[9500];
		random.nextBytes(blob);
		return new AsyncEnvelope(AsyncEnvelope.PREKEY_KIND_SIGNED,
				new byte[AsyncEnvelope.PREKEY_ID_BYTES],
				AsyncPrekeyStore.PUBLISHED_SIGNED_PREKEY_ID, ephemeral,
				kemCiphertext, ttl, dedup, blob).encode();
	}

	@Test
	public void coverEnvelopesCarryThePublishedSignedPrekeyId()
			throws Exception {
		AsyncPrekeyStore store = new AsyncPrekeyStore(crypto,
				new InMemorySettingsManager(), new SystemClock());
		AsyncMeshDelivery d = new AsyncMeshDelivery(crypto,
				new AsyncSealedSender(crypto), store, (a, b, c, e) -> true,
				sender, new SystemClock());
		List<byte[]> captured = new ArrayList<>();
		MeshForwarder f = new MeshForwarder(captured::add, random);
		MeshForwarder origin = new MeshForwarder(d, random);
		origin.addLink(link(f));
		for (int i = 0; i < 4; i++) {
			d.sendCover(origin, new byte[4096], 3600L,
					System.currentTimeMillis(), false);
		}
		assertEquals(4, captured.size());
		for (byte[] env : captured) {
			assertEquals(AsyncPrekeyStore.PUBLISHED_SIGNED_PREKEY_ID,
					AsyncEnvelope.decode(env).getSignedPrekeyId());
		}
	}

	private static Set<String> ids(AsyncPrekeyBundle b) {
		Set<String> out = new HashSet<>();
		for (AsyncPrekeyBundle.OneTimePrekey p : b.getOneTimePrekeys()) {
			out.add(StringUtils.toHexString(p.id));
		}
		return out;
	}

	private AsyncMeshDelivery.Identity identity(byte[] agreePub) {
		KeyPair sig = crypto.generateHybridSignatureKeyPair();
		byte[] agree = agreePub != null ? agreePub
				: crypto.generateHybridAgreementKeyPair().getPublic()
						.getEncoded();
		return new AsyncMeshDelivery.Identity(sig.getPublic().getEncoded(),
				sig.getPrivate(), agree);
	}

	private byte[] seal(AsyncPrekeyBundle bundle, boolean oneTime)
			throws Exception {
		return sealAt(bundle, oneTime, 3600L, System.currentTimeMillis());
	}

	private byte[] sealAt(AsyncPrekeyBundle bundle, boolean oneTime,
			long ttl, long sentAt) throws Exception {
		AsyncPrekeyStore sStore = new AsyncPrekeyStore(crypto,
				new InMemorySettingsManager(), new SystemClock());
		Clock senderClock = new MutableClock(sentAt);
		AsyncMeshDelivery d = new AsyncMeshDelivery(crypto,
				new AsyncSealedSender(crypto), sStore, (a, b, c, e) -> true,
				sender, senderClock);
		List<byte[]> captured = new ArrayList<>();
		MeshForwarder relay = new MeshForwarder(captured::add, random);
		MeshForwarder origin = new MeshForwarder(d, random);
		origin.addLink(link(relay));
		d.send(origin, bundle, 9, "x".getBytes(), ttl, sentAt, oneTime);
		return captured.get(0);
	}

	private static MeshLink link(MeshForwarder target) {
		return new MeshLink() {
			@Override
			public String getId() {
				return "l";
			}

			@Override
			public void broadcast(byte[] frame) {
				target.onReceive(frame, "in");
			}
		};
	}

	private final class Recipient {
		final InMemorySettingsManager settings = new InMemorySettingsManager();
		final AsyncPrekeyStore store;
		final byte[] legacyAgreePub;
		final AsyncMeshDelivery.Identity identity;
		final AsyncMeshDelivery delivery;
		final List<byte[]> opened = new ArrayList<>();
		final int pool;

		Recipient(Clock clock) throws Exception {
			this(clock, 5);
		}

		Recipient(Clock clock, int pool) throws Exception {
			this.pool = pool;
			store = new AsyncPrekeyStore(counting, settings, clock);
			legacyAgreePub = crypto.generateHybridAgreementKeyPair()
					.getPublic().getEncoded();
			KeyPair sig = crypto.generateHybridSignatureKeyPair();
			identity = new AsyncMeshDelivery.Identity(
					sig.getPublic().getEncoded(), sig.getPrivate(),
					store.getMeshAgreementPublicKey(), legacyAgreePub);
			delivery = new AsyncMeshDelivery(counting,
					new AsyncSealedSender(counting), store,
					(s, t, p, ts) -> opened.add(p), identity, clock);
		}

		AsyncPrekeyBundle bundleFor(int contact) throws Exception {
			byte[] audience = {0, 0, 0, (byte) contact};
			List<AsyncPrekeyBundle.OneTimePrekey> otks =
					store.topUpOneTimePrekeys(pool, audience);
			AsyncPrekeyStore.SignedPrekey spk = store.getSignedPrekey();
			return AsyncPrekeyBundle.create(crypto, identity.sigPub,
					identity.sigPriv, identity.agreePub,
					AsyncPrekeyStore.PUBLISHED_SIGNED_PREKEY_ID, spk.pub,
					spk.expiry, otks);
		}
	}

	private static final class MutableClock implements Clock {
		volatile long now;

		MutableClock(long now) {
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
}
