package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.crypto.async.AsyncEnvelope;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.AsyncPrekeyStore;
import org.zerionproject.core.system.SystemClock;
import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class AsyncPrekeyStoreTest {

	private CryptoComponent crypto;
	private AsyncPrekeyStore store;

	@Before
	public void setUp() {
		crypto = new CryptoComponentImpl(() -> null,
				new ScryptKdf(new SystemClock()));
		store = new AsyncPrekeyStore(crypto, new InMemorySettingsManager(),
				new SystemClock());
	}

	@Test
	public void generatesResolvesAndConsumesOneTimePrekeys() throws Exception {
		List<AsyncPrekeyBundle.OneTimePrekey> created =
				store.generateOneTimePrekeys(3);
		assertEquals(3, created.size());
		byte[] id = created.get(0).id;
		KeyPair kp = store.resolvePrekey(AsyncEnvelope.PREKEY_KIND_ONE_TIME,
				id, 0L);
		assertNotNull(kp);
		store.consumeOneTimePrekey(id);
		assertNull(store.resolvePrekey(AsyncEnvelope.PREKEY_KIND_ONE_TIME,
				id, 0L));
	}

	@Test
	public void signedPrekeyIsStableThenRotates() throws Exception {
		AsyncPrekeyStore.SignedPrekey a = store.getSignedPrekey();
		assertNotNull(a);
		AsyncPrekeyStore.SignedPrekey b = store.getSignedPrekey();
		assertEquals(a.id, b.id);
		AsyncPrekeyStore.SignedPrekey c = store.rotateSignedPrekey();
		assertEquals(a.id + 1, c.id);
		// The previous signed prekey is still resolvable during the grace window.
		assertNotNull(store.resolvePrekey(AsyncEnvelope.PREKEY_KIND_SIGNED,
				new byte[AsyncEnvelope.PREKEY_ID_BYTES], a.id));
		assertNotNull(store.resolvePrekey(AsyncEnvelope.PREKEY_KIND_SIGNED,
				new byte[AsyncEnvelope.PREKEY_ID_BYTES], c.id));
	}

	@Test
	public void dedupRejectsRepeats() throws Exception {
		byte[] id = new byte[AsyncEnvelope.DEDUP_ID_BYTES];
		long expiry = System.currentTimeMillis() + 60_000L;
		assertTrue(store.checkAndMarkSeen(id, expiry));
		assertFalse(store.checkAndMarkSeen(id, expiry));
	}

	/**
	 * CRY-08: the bound evicts the entry expiring soonest and raises the
	 * floor to its expiry, so the evicted id, any id expiring at or before
	 * the floor, and an expired id are all refused; only later expiries are
	 * admitted, and a newcomer that would itself be the soonest to expire is
	 * refused rather than admitted and forgotten.
	 */
	@Test
	public void evictionRaisesTheFloorInsteadOfForgetting() throws Exception {
		AsyncPrekeyStore bounded = new AsyncPrekeyStore(crypto,
				new InMemorySettingsManager(), new SystemClock(), 2);
		long now = System.currentTimeMillis();
		byte[] a = id(1), b = id(2), c = id(3), d = id(4), e = id(5), f = id(6);
		assertTrue(bounded.checkAndMarkSeen(a, now + 100_000L));
		assertTrue(bounded.checkAndMarkSeen(b, now + 200_000L));
		assertTrue(bounded.checkAndMarkSeen(c, now + 300_000L));
		assertFalse(bounded.isSeen(a));
		assertEquals(now + 100_000L, bounded.seenFloor());
		assertFalse("evicted id replayed", bounded.checkAndMarkSeen(a,
				now + 100_000L));
		assertFalse("unseen id at the floor", bounded.checkAndMarkSeen(d,
				now + 100_000L));
		assertFalse("newcomer that would expire first is refused",
				bounded.checkAndMarkSeen(e, now + 150_000L));
		assertEquals(now + 150_000L, bounded.seenFloor());
		assertTrue(bounded.checkAndMarkSeen(f, now + 400_000L));
		assertFalse(bounded.isSeen(b));
		assertEquals(now + 200_000L, bounded.seenFloor());
		assertFalse("already expired", bounded.checkAndMarkSeen(id(7),
				now - 1L));
	}

	/** Entries written before expiries were recorded are kept, not dropped. */
	@Test
	public void legacyEntriesWithoutExpiryStayRemembered() throws Exception {
		InMemorySettingsManager settings = new InMemorySettingsManager();
		Settings legacy = new Settings();
		legacy.put("seen", "0A0A0A0A0A0A0A0A0A0A0A0A0A0A0A0A");
		settings.mergeSettings(legacy, "org.zerionproject.async/prekeys");
		AsyncPrekeyStore s = new AsyncPrekeyStore(crypto, settings,
				new SystemClock());
		byte[] legacyId = new byte[AsyncEnvelope.DEDUP_ID_BYTES];
		java.util.Arrays.fill(legacyId, (byte) 0x0a);
		assertTrue(s.isSeen(legacyId));
		assertFalse(s.checkAndMarkSeen(legacyId,
				System.currentTimeMillis() + 60_000L));
		assertTrue(s.checkAndMarkSeen(id(9),
				System.currentTimeMillis() + 60_000L));
		assertTrue(s.isSeen(legacyId));
	}

	private static byte[] id(int v) {
		byte[] b = new byte[AsyncEnvelope.DEDUP_ID_BYTES];
		b[0] = (byte) v;
		return b;
	}
}
