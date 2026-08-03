package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
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
		assertTrue(store.checkAndMarkSeen(id));
		assertFalse(store.checkAndMarkSeen(id));
	}
}
