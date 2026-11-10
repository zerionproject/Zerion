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
		assertTrue("a dial that authenticated cleared the mark by itself",
				store.isOutOfSync(contact));
		for (int i = 0; i < RootEvolutionManager.OUT_OF_SYNC_AFTER - 1; i++) {
			manager.dialEnded(contact, false);
		}
		manager.connectionEnded(contact, false);
		assertFalse("a connection on which no renewal was outstanding",
				store.isOutOfSync(contact));
		manager.dialEnded(contact, false);
		assertTrue("the dial that authenticated did not end the run of"
				+ " failed dials", store.isOutOfSync(contact));
		manager.evolved(contact);
		assertFalse(store.isOutOfSync(contact));
	}

	@Test
	public void connectionsWithARenewalOutstandingAreCountedUntilOneCompletes() {
		for (int i = 1; i < RootEvolutionManager
				.NOT_RENEWING_AFTER_CONNECTIONS; i++) {
			manager.connectionEnded(contact, true);
			assertEquals(i, store.stalls(contact)[0]);
			assertEquals(1_000_000L, store.stalls(contact)[1]);
			assertFalse("connection " + i, store.isNotRenewing(contact));
			clock.addAndGet(1_000);
		}
		manager.connectionEnded(contact, false);
		assertFalse(store.isNotRenewing(contact));
		assertEquals("a connection with nothing outstanding ended the run",
				RootEvolutionManager.NOT_RENEWING_AFTER_CONNECTIONS - 1,
				store.stalls(contact)[0]);
		manager.connectionEnded(contact, true);
		assertTrue(store.isNotRenewing(contact));
		assertFalse("another contact is not affected",
				store.isNotRenewing(new ContactId(8)));
		manager.connectionEnded(contact, false);
		assertTrue("the warning went away without a renewal",
				store.isNotRenewing(contact));
		manager.evolved(contact);
		assertFalse(store.isNotRenewing(contact));
		assertEquals(0, store.stalls(contact)[0]);
		manager.connectionEnded(contact, true);
		assertEquals(1, store.stalls(contact)[0]);
		assertFalse(store.isNotRenewing(contact));
	}

	@Test
	public void aRenewalOutstandingForAnHourRaisesTheWarningAtTheNextConnection() {
		manager.connectionEnded(contact, true);
		clock.addAndGet(RootEvolutionManager.NOT_RENEWING_AFTER_MS - 1);
		manager.connectionEnded(contact, true);
		assertFalse(store.isNotRenewing(contact));
		clock.addAndGet(1);
		manager.connectionEnded(contact, true);
		assertTrue(store.isNotRenewing(contact));
	}

	@Test
	public void twoStallsFarApartDoNotRaiseTheWarningByThemselves() {
		manager.connectionEnded(contact, true);
		clock.addAndGet(24 * 60 * 60_000L);
		manager.connectionEnded(contact, true);
		assertEquals(2, store.stalls(contact)[0]);
		assertFalse(store.isNotRenewing(contact));
		manager.connectionEnded(contact, true);
		assertTrue(store.isNotRenewing(contact));
	}

	@Test
	public void aStallBesideAnotherConnectionIsCountedWhenTheLastOneEnds() {
		manager.stalledBesideAnother(contact);
		manager.connectionEnded(contact, false);
		assertEquals(1, store.stalls(contact)[0]);
		manager.stalledBesideAnother(contact);
		manager.stalledBesideAnother(contact);
		manager.connectionEnded(contact, true);
		assertEquals("counted once per group", 2, store.stalls(contact)[0]);
		manager.stalledBesideAnother(contact);
		manager.evolved(contact);
		manager.connectionEnded(contact, false);
		assertEquals("a renewal forgets the stall beside it", 0,
				store.stalls(contact)[0]);
	}

	@Test
	public void aClockThatWentBackStartsTheHourAgain() {
		manager.connectionEnded(contact, true);
		clock.addAndGet(-500_000L);
		manager.connectionEnded(contact, true);
		assertEquals(1, store.stalls(contact)[0]);
		assertEquals(500_000L, store.stalls(contact)[1]);
		clock.addAndGet(RootEvolutionManager.NOT_RENEWING_AFTER_MS - 1);
		manager.connectionEnded(contact, true);
		assertFalse(store.isNotRenewing(contact));
	}
}
