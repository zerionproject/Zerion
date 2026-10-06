package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.transport.RootEvolutionTestBed.MemRootKeyStore;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RootEvolutionManagerTest {

	private final ContactId contact = new ContactId(7);
	private final AtomicLong clock = new AtomicLong(1_000_000L);
	private RootEvolutionTestBed bed;
	private MemRootKeyStore store;
	private RootEvolutionManager manager;

	@Before
	public void setUp() throws Exception {
		bed = new RootEvolutionTestBed();
		store = new MemRootKeyStore();
		manager = new RootEvolutionManager(store, bed.crypto, bed.mlKem,
				clock::get);
	}

	@Test
	public void aContactsOffersAreAnsweredAtMostThreeTimesPerInterval() {
		for (int i = 0; i < RootEvolutionManager.MAX_ANSWERS_PER_INTERVAL;
				i++) {
			assertTrue("answer " + i, manager.mayAnswer(contact));
		}
		assertFalse(manager.mayAnswer(contact));
		assertTrue("another contact is not affected",
				manager.mayAnswer(new ContactId(8)));
		clock.addAndGet(RootEvolutionManager.ANSWER_INTERVAL_MS);
		assertTrue(manager.mayAnswer(contact));
	}

	@Test
	public void theDiallerFallsBackToTheCurrentRootAfterFailedDials() {
		SecretKey current = bed.randomKey();
		SecretKey pending = bed.randomKey();
		ContactRootKeys confirmed =
				new ContactRootKeys(3, current, pending, true);
		ContactRootKeys unconfirmed =
				new ContactRootKeys(3, current, pending, false);
		assertEquals(4, manager.dialEpoch(contact, confirmed));
		assertEquals(3, manager.dialEpoch(contact, unconfirmed));
		for (int i = 0; i < RootEvolutionManager.PENDING_DIAL_FALLBACK_AFTER;
				i++) {
			assertEquals(4, manager.dialEpoch(contact, confirmed));
			manager.dialEnded(contact, false);
		}
		assertEquals("one dial under the current root", 3,
				manager.dialEpoch(contact, confirmed));
		manager.dialEnded(contact, false);
		assertEquals("then the pending root again", 4,
				manager.dialEpoch(contact, confirmed));
		manager.dialEnded(contact, true);
		assertEquals(4, manager.dialEpoch(contact, confirmed));
		manager.dialEnded(contact, false);
		assertEquals(4, manager.dialEpoch(contact, confirmed));
	}

	@Test
	public void acceptedButUnauthenticatedDialsMarkTheKeysOutOfSync() {
		store.put(contact, ContactRootKeys.atPairing(bed.randomKey()));
		for (int i = 0; i < RootEvolutionManager.OUT_OF_SYNC_AFTER - 1; i++) {
			manager.dialEnded(contact, false);
			assertFalse("dial " + i, store.isOutOfSync(contact));
		}
		manager.dialEnded(contact, false);
		assertTrue(store.isOutOfSync(contact));
		manager.dialEnded(contact, false);
		assertTrue(store.isOutOfSync(contact));
		manager.dialEnded(contact, true);
		assertFalse(store.isOutOfSync(contact));
	}
}
