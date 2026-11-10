package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.transport.RootEvolutionTestBed.Device;
import org.zerionproject.transport.RootEvolutionTestBed.Hold;
import org.zerionproject.transport.RootEvolutionTestBed.Live;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.transport.RootEvolutionManager.NOT_RENEWING_AFTER_CONNECTIONS;
import static org.zerionproject.transport.RootEvolutionManager.NOT_RENEWING_AFTER_MS;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_HELLO;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_RESP;

public class RootEvolutionStallWarningTest {

	private static final int ALICE_SEES_BOB = 2;
	private static final int BOB_SEES_ALICE = 1;
	private static final long PATIENT_MS = 60_000;
	private static final long LONG_MS = 60 * 60_000L;
	private static final ContactId BOB = new ContactId(ALICE_SEES_BOB);
	private static final ContactId ALICE = new ContactId(BOB_SEES_ALICE);

	private RootEvolutionTestBed bed;
	private Device alice;
	private Device bob;

	@Before
	public void setUp() throws Exception {
		bed = new RootEvolutionTestBed();
		SecretKey pairingRoot = bed.randomKey();
		alice = bed.new Device("alice", ALICE_SEES_BOB, true,
				ContactRootKeys.atPairing(copy(pairingRoot)), true);
		bob = bed.new Device("bob", BOB_SEES_ALICE, false,
				ContactRootKeys.atPairing(copy(pairingRoot)), true);
		manager(alice).setTimings(PATIENT_MS, LONG_MS);
		manager(bob).setTimings(PATIENT_MS, LONG_MS);
		manager(alice).setNudge(200);
		manager(bob).setNudge(200);
	}

	private static RootEvolutionManager manager(Device d) {
		RootEvolutionManager m = d.evolutions;
		if (m == null) throw new AssertionError();
		return m;
	}

	private void meetOnce() throws Exception {
		assertTrue(bed.exchange(alice, bob));
		assertEquals(1, alice.keys().getEpoch());
		assertTrue(alice.store.peerEnforces(BOB));
		assertTrue(bob.store.peerEnforces(ALICE));
	}

	private void droppedBeforeTheAnswerArrives() throws Exception {
		int aliceBefore = alice.received.size();
		int bobBefore = bob.received.size();
		long stores = bob.store.pendingStores.get();
		int greetings = greetingsHandledBy(alice);
		bob.withheld = KIND_RESP;
		Live live = bed.new Live(alice, bob);
		assertTrue("bob never answered", live.await(
				() -> bob.store.pendingStores.get() > stores, 20_000));
		assertTrue("alice never saw bob's greeting", live.await(
				() -> greetingsHandledBy(alice) > greetings, 20_000));
		live.close();
		bob.withheld = 0;
		assertEquals("records left on a connection that never evolved",
				aliceBefore, alice.received.size());
		assertEquals(bobBefore, bob.received.size());
	}

	private static int greetingsHandledBy(Device d) {
		int n = 0;
		synchronized (d.handledKinds) {
			for (Byte kind : d.handledKinds) {
				if (kind == KIND_HELLO) n++;
			}
		}
		return n;
	}

	@Test(timeout = 180_000)
	public void connectionsThatAuthenticateButNeverRenewRaiseTheWarningOnBothSides()
			throws Exception {
		meetOnce();
		bed.passTime();
		alice.store.markOutOfSync(BOB, true);
		for (int i = 1; i <= NOT_RENEWING_AFTER_CONNECTIONS; i++) {
			droppedBeforeTheAnswerArrives();
			assertEquals("connection " + i, i, alice.store.stalls(BOB)[0]);
			assertEquals("connection " + i, i, bob.store.stalls(ALICE)[0]);
			boolean due = i == NOT_RENEWING_AFTER_CONNECTIONS;
			assertEquals("alice after connection " + i, due,
					alice.store.isNotRenewing(BOB));
			assertEquals("bob after connection " + i, due,
					bob.store.isNotRenewing(ALICE));
			assertTrue("a connection that authenticated without renewing"
							+ " the root cleared the out of sync mark",
					alice.store.isOutOfSync(BOB));
			assertEquals(1, alice.keys().getEpoch());
		}
		bed.passTime();
		assertTrue("a renewal that is let through completes",
				bed.exchange(alice, bob));
		assertEquals(2, alice.keys().getEpoch());
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertFalse(alice.store.isNotRenewing(BOB));
		assertFalse(bob.store.isNotRenewing(ALICE));
		assertFalse(alice.store.isOutOfSync(BOB));
		assertEquals(0, alice.store.stalls(BOB)[0]);
		assertEquals(0, bob.store.stalls(ALICE)[0]);
	}

	@Test(timeout = 120_000)
	public void aRenewalThatHasBeenFailingForAnHourRaisesTheWarningSooner()
			throws Exception {
		meetOnce();
		bed.passTime();
		droppedBeforeTheAnswerArrives();
		bed.clock.addAndGet(NOT_RENEWING_AFTER_MS - 60_000);
		droppedBeforeTheAnswerArrives();
		assertEquals(2, alice.store.stalls(BOB)[0]);
		assertFalse("two failures within the hour raised the warning",
				alice.store.isNotRenewing(BOB));
		assertFalse(bob.store.isNotRenewing(ALICE));
		bed.clock.addAndGet(60_000);
		droppedBeforeTheAnswerArrives();
		assertTrue(alice.store.isNotRenewing(BOB));
		assertTrue(bob.store.isNotRenewing(ALICE));
	}

	@Test(timeout = 120_000)
	public void aConnectionOnWhichNoRenewalWasDueClearsTheOutOfSyncMarkOnly()
			throws Exception {
		meetOnce();
		alice.store.markOutOfSync(BOB, true);
		assertTrue(bed.exchange(alice, bob));
		assertEquals("a renewal ran although none was due", 1,
				alice.keys().getEpoch());
		assertFalse(alice.store.isOutOfSync(BOB));
		assertEquals(0, alice.store.stalls(BOB)[0]);

		bed.passTime();
		droppedBeforeTheAnswerArrives();
		assertEquals(1, alice.store.stalls(BOB)[0]);
		alice.store.markOutOfSync(BOB, true);
		bob.store.paused = true;
		assertTrue("records wait for a paused side",
				bed.exchange(alice, bob));
		bob.store.paused = false;
		assertEquals(1, alice.keys().getEpoch());
		assertFalse("a side that says it is paused is in sync",
				alice.store.isOutOfSync(BOB));
		assertEquals("a paused connection is no renewal", 1,
				alice.store.stalls(BOB)[0]);
		assertFalse(alice.store.isNotRenewing(BOB));
	}

	@Test(timeout = 120_000)
	public void aPeerOfAnEarlierReleaseIsNotCounted() throws Exception {
		manager(bob).setEnforcing(false);
		assertTrue(bed.exchange(alice, bob));
		assertEquals(1, alice.keys().getEpoch());
		assertFalse(alice.store.peerEnforces(BOB));
		bed.passTime();
		alice.store.markOutOfSync(BOB, true);
		for (int i = 0; i < NOT_RENEWING_AFTER_CONNECTIONS; i++) {
			int before = alice.received.size();
			Hold hold = new Hold(KIND_RESP);
			bob.hold = hold;
			Live live = bed.new Live(alice, bob);
			assertTrue(live.await(() -> hold.caught
					&& alice.received.size() > before, 20_000));
			live.close();
			bob.hold = null;
		}
		assertEquals(1, alice.keys().getEpoch());
		assertEquals(0, alice.store.stalls(BOB)[0]);
		assertFalse(alice.store.isNotRenewing(BOB));
		assertFalse("with a peer that does not promise to renew, a"
						+ " connection that authenticates is all there is",
				alice.store.isOutOfSync(BOB));
	}

	@Test
	public void aConnectionThatNeverAuthenticatedOrRanBesideAnotherIsNotCounted() {
		RootEvolutionManager m = manager(alice);
		m.evolved(BOB);
		alice.store.setPeerEvolves(BOB);
		alice.store.setPeerEnforces(BOB, true);
		bed.passTime();
		alice.store.markOutOfSync(BOB, true);
		RootEvolution silent = new RootEvolution(m, BOB, true);
		silent.start(ZwfControlHandler.eager(p -> {
		}));
		assertTrue(silent.holdsUserRecords());
		silent.close();
		assertEquals("a connection on which nothing authenticated", 0,
				alice.store.stalls(BOB)[0]);
		assertTrue(alice.store.isOutOfSync(BOB));

		RootEvolution first = new RootEvolution(m, BOB, true);
		RootEvolution second = new RootEvolution(m, BOB, true);
		first.start(ZwfControlHandler.eager(p -> {
		}));
		second.start(ZwfControlHandler.eager(p -> {
		}));
		first.onPeerStreamAuthenticated(1);
		second.onPeerStreamAuthenticated(1);
		first.close();
		assertEquals("the redundant connection was counted", 0,
				alice.store.stalls(BOB)[0]);
		second.close();
		second.close();
		assertEquals("counted once, also when closed twice", 1,
				alice.store.stalls(BOB)[0]);
		assertTrue(alice.store.isOutOfSync(BOB));
	}

	private static SecretKey copy(SecretKey k) {
		return new SecretKey(k.getBytes().clone());
	}
}
