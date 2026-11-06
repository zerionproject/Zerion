package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.transport.RootEvolutionTestBed.Cut;
import org.zerionproject.transport.RootEvolutionTestBed.Device;
import org.zerionproject.transport.RootEvolutionTestBed.Hold;
import org.zerionproject.transport.RootEvolutionTestBed.Live;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_CONFIRM;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_DONE;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_HELLO;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_RESP;

public class RootEvolutionDeadlineTest {

	private static final int ALICE_SEES_BOB = 2;
	private static final int BOB_SEES_ALICE = 1;
	private static final long PATIENT_MS = 60_000;
	private static final long SHORT_MS = 1_500;
	private static final long STAY_MS = 4_000;
	private static final long LONG_MS = 60 * 60_000L;
	private static final ContactId BOB = new ContactId(ALICE_SEES_BOB);
	private static final ContactId ALICE = new ContactId(BOB_SEES_ALICE);

	private RootEvolutionTestBed bed;
	private SecretKey pairingRoot;
	private Device alice;
	private Device bob;

	@Before
	public void setUp() throws Exception {
		bed = new RootEvolutionTestBed();
		pairingRoot = bed.randomKey();
		alice = bed.new Device("alice", ALICE_SEES_BOB, true,
				ContactRootKeys.atPairing(copy(pairingRoot)), true);
		bob = bed.new Device("bob", BOB_SEES_ALICE, false,
				ContactRootKeys.atPairing(copy(pairingRoot)), true);
		timings(PATIENT_MS, LONG_MS);
		manager(alice).setNudge(200);
		manager(bob).setNudge(200);
	}

	private void timings(long deadlineMs, long liveMs) {
		manager(alice).setTimings(deadlineMs, liveMs);
		manager(bob).setTimings(deadlineMs, liveMs);
	}

	private static RootEvolutionManager manager(Device d) {
		RootEvolutionManager m = d.evolutions;
		if (m == null) throw new AssertionError();
		return m;
	}

	private void meetOnce() throws Exception {
		assertTrue(bed.exchange(alice, bob));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertEquals(1, alice.keys().getEpoch());
		assertTrue(alice.store.peerEnforces(BOB));
		assertTrue(bob.store.peerEnforces(ALICE));
		assertTrue(alice.store.peerEvolves(BOB));
		assertTrue(bob.store.peerEvolves(ALICE));
	}

	private int[] seen() {
		return new int[] {alice.received.size(), bob.received.size(),
				alice.connections.get(), bob.connections.get()};
	}

	private boolean nothingSince(int[] before) {
		return alice.received.size() == before[0]
				&& bob.received.size() == before[1];
	}

	private boolean nothingPreparedSince(int[] before) {
		return alice.connections.get() == before[2]
				&& bob.connections.get() == before[3];
	}

	private static void awaitClosed(Live live) throws InterruptedException {
		long opened = System.currentTimeMillis();
		while (live.isOpen()
				&& System.currentTimeMillis() - opened < 30_000) {
			Thread.sleep(20);
		}
		assertFalse("the connection was not closed", live.isOpen());
		live.close();
	}

	@Test(timeout = 120_000)
	public void nothingIsSentUntilAnEvolutionThatIsDueHasCompleted()
			throws Exception {
		meetOnce();
		bed.passTime();
		int[] before = seen();
		bob.withheld = KIND_RESP;
		Live live = bed.new Live(alice, bob);
		assertTrue("bob answers", live.await(
				() -> bob.keys().getPending() != null, 20_000));
		Thread.sleep(1_000);
		assertTrue(live.isOpen());
		assertTrue("records left before the evolution completed",
				nothingSince(before));
		assertTrue("records were taken from the store while they could "
				+ "not be sent", nothingPreparedSince(before));
		assertEquals(1, alice.keys().getEpoch());
		bob.withheld = 0;
		assertTrue("the evolution completes", live.await(
				() -> RootEvolutionTestBed.settled(alice, bob, 1), 20_000));
		assertTrue("records flow afterwards", live.await(
				() -> alice.received.size() > before[0]
						&& bob.received.size() > before[1], 20_000));
		live.close();
		assertEquals(2, alice.keys().getEpoch());
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
	}

	@Test(timeout = 120_000)
	public void theOfferingSideSendsNothingOnARootTheOtherSideHasNotConfirmed()
			throws Exception {
		meetOnce();
		bed.passTime();
		int[] before = seen();
		alice.withheld = KIND_CONFIRM;
		Live live = bed.new Live(alice, bob);
		assertTrue(live.await(() -> alice.keys().isPendingConfirmed(),
				20_000));
		Thread.sleep(1_000);
		assertTrue("records left while only alice held the new root",
				nothingSince(before));
		assertEquals(1, bob.keys().getEpoch());
		alice.withheld = 0;
		assertTrue(live.await(() -> alice.received.size() > before[0]
				&& bob.received.size() > before[1], 20_000));
		live.close();
		assertEquals(2, alice.keys().getEpoch());
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
	}

	@Test(timeout = 120_000)
	public void theOfferingSideWaitsForTheLastStepBeforeItSends()
			throws Exception {
		meetOnce();
		bed.passTime();
		int[] before = seen();
		Hold done = new Hold(KIND_DONE);
		bob.hold = done;
		Live live = bed.new Live(alice, bob);
		assertTrue(live.await(() -> done.caught, 20_000));
		assertEquals(2, bob.keys().getEpoch());
		Thread.sleep(600);
		assertEquals("alice sent before the evolution completed for her",
				before[1], bob.received.size());
		assertEquals(1, alice.keys().getEpoch());
		bob.hold = null;
		done.release();
		assertTrue(live.await(() -> bob.received.size() > before[1], 20_000));
		live.close();
		assertEquals(2, alice.keys().getEpoch());
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
	}

	@Test(timeout = 120_000)
	public void aCutBeforeTheLastStepLeavesTheOfferingSideWaiting()
			throws Exception {
		meetOnce();
		bed.passTime();
		int[] before = seen();
		Cut cut = new Cut("alice", KIND_CONFIRM);
		alice.cut = cut;
		bed.connect(alice, bob, () -> cut.fired);
		alice.cut = null;
		assertTrue(cut.fired);
		assertTrue(alice.keys().isPendingConfirmed());
		assertTrue("an evolution only alice finished counts as done",
				manager(alice).isDue(BOB));
		assertTrue(nothingSince(before));
		assertTrue(bed.exchange(alice, bob));
		assertEquals(2, alice.keys().getEpoch());
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
	}

	@Test(timeout = 120_000)
	public void aBlockedEvolutionEndsTheConnectionWithNothingSent()
			throws Exception {
		meetOnce();
		timings(SHORT_MS, LONG_MS);
		for (int round = 0; round < 3; round++) {
			bed.passTime();
			int[] before = seen();
			bob.withheld = KIND_RESP;
			Live live = bed.new Live(alice, bob);
			awaitClosed(live);
			bob.withheld = 0;
			assertTrue("records left on a connection that never evolved",
					nothingSince(before));
			assertTrue(nothingPreparedSince(before));
			assertEquals(1, alice.keys().getEpoch());
			assertTrue("the dialler is not told that the renewal stalled",
					manager(alice).recentlyStalled(ALICE_SEES_BOB));
		}
		timings(PATIENT_MS, LONG_MS);
		bed.passTime();
		assertTrue("an evolution that is let through restores the flow",
				bed.exchange(alice, bob));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertEquals(2, alice.keys().getEpoch());
	}

	@Test(timeout = 120_000)
	public void aConnectionWhoseGreetingNeverArrivesSendsNothingAndIsClosed()
			throws Exception {
		meetOnce();
		timings(SHORT_MS, LONG_MS);
		bed.passTime();
		int[] before = seen();
		bob.withheld = KIND_HELLO;
		Live live = bed.new Live(alice, bob);
		awaitClosed(live);
		bob.withheld = 0;
		assertTrue("records left without a greeting from the answering side",
				nothingSince(before));
		assertEquals(1, alice.keys().getEpoch());
		assertEquals(1, bob.keys().getEpoch());
		timings(PATIENT_MS, LONG_MS);
		bed.passTime();
		assertTrue(bed.exchange(alice, bob));
		assertEquals(2, alice.keys().getEpoch());
	}

	@Test(timeout = 120_000)
	public void aSideThatNeverGotAGreetingStillWaitsForOneOnceTheRootHasEvolved()
			throws Exception {
		SecretKey root = bed.randomKey();
		Device a = bed.new Device("alice", ALICE_SEES_BOB, true,
				new ContactRootKeys(3, copy(root), null, null, false), true);
		Device b = bed.new Device("bob", BOB_SEES_ALICE, false,
				new ContactRootKeys(3, copy(root), null, null, false), true);
		manager(a).setTimings(SHORT_MS, LONG_MS);
		manager(b).setTimings(SHORT_MS, LONG_MS);
		assertFalse(a.store.peerEnforces(BOB));
		assertFalse(a.store.peerEvolves(BOB));
		for (int round = 0; round < 2; round++) {
			b.withheld = KIND_HELLO;
			Live live = bed.new Live(a, b);
			awaitClosed(live);
			b.withheld = 0;
			assertEquals("alice sent to a peer whose greeting was withheld",
					0, b.received.size());
			assertEquals(0, a.received.size());
			assertEquals(0, a.connections.get());
			assertEquals(3, a.keys().getEpoch());
		}
		manager(a).setTimings(PATIENT_MS, LONG_MS);
		manager(b).setTimings(PATIENT_MS, LONG_MS);
		assertTrue(bed.exchange(a, b));
		assertEquals(4, a.keys().getEpoch());
		assertTrue(RootEvolutionTestBed.sameRoot(a, b));
	}

	@Test(timeout = 120_000)
	public void withoutTheOfferingSidesGreetingRecordsStillWaitForTheEvolution()
			throws Exception {
		meetOnce();
		bed.passTime();
		int[] before = seen();
		alice.withheld = KIND_HELLO;
		Hold resp = new Hold(KIND_RESP);
		bob.hold = resp;
		Live live = bed.new Live(alice, bob);
		assertTrue("alice offers on bob's greeting alone",
				live.await(() -> resp.caught, 20_000));
		Thread.sleep(500);
		assertTrue(nothingSince(before));
		bob.hold = null;
		resp.release();
		assertTrue(live.await(() -> alice.received.size() > before[0]
				&& bob.received.size() > before[1], 20_000));
		live.close();
		alice.withheld = 0;
		assertEquals(2, alice.keys().getEpoch());
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
	}

	@Test(timeout = 120_000)
	public void anEvolutionThatIsNotDueHoldsNothingBack() throws Exception {
		meetOnce();
		timings(STAY_MS, LONG_MS);
		int[] before = seen();
		Live live = bed.new Live(alice, bob);
		assertTrue(live.await(() -> alice.received.size() > before[0]
				&& bob.received.size() > before[1], 20_000));
		Thread.sleep(STAY_MS + 500);
		assertTrue("a connection without a due evolution was closed",
				live.isOpen());
		live.close();
		assertEquals(1, alice.keys().getEpoch());
		assertFalse(manager(alice).recentlyStalled(ALICE_SEES_BOB));
	}

	@Test(timeout = 180_000)
	public void aSideThatIsPausedOrWritingABackupIsNotWaitedFor()
			throws Exception {
		meetOnce();
		for (int round = 0; round < 2; round++) {
			Device paused = round == 0 ? bob : alice;
			timings(STAY_MS, LONG_MS);
			bed.passTime();
			if (round == 0) paused.store.paused = true;
			else paused.store.snapshotting = true;
			int[] before = seen();
			Live live = bed.new Live(alice, bob);
			assertTrue("records wait for a paused " + paused.name,
					live.await(() -> alice.received.size() > before[0]
							&& bob.received.size() > before[1], 20_000));
			Thread.sleep(STAY_MS + 500);
			assertTrue("closed although " + paused.name + " is paused",
					live.isOpen());
			live.close();
			paused.store.snapshotting = false;
			paused.store.paused = false;
			assertEquals(1, alice.keys().getEpoch());
			assertEquals(1, bob.keys().getEpoch());
		}
		timings(PATIENT_MS, LONG_MS);
		bed.passTime();
		assertTrue(bed.exchange(alice, bob));
		assertEquals(2, alice.keys().getEpoch());
	}

	@Test(timeout = 180_000)
	public void aSideThatBecomesPausedOnAnOpenConnectionIsLeftAloneUntilItResumes()
			throws Exception {
		meetOnce();
		for (Device paused : new Device[] {bob, alice}) {
			timings(STAY_MS, 500);
			bed.clock.addAndGet(RootEvolutionManager.ANSWER_INTERVAL_MS + 1);
			long start = alice.keys().getEpoch();
			Live live = bed.new Live(alice, bob);
			assertTrue("the open connection evolves", live.await(
					() -> alice.keys().getEpoch() > start
							&& RootEvolutionTestBed.sameRoot(alice, bob),
					30_000));
			paused.store.paused = true;
			Thread.sleep(1_200);
			long whilePaused = alice.keys().getEpoch();
			Thread.sleep(STAY_MS + 1_000);
			assertTrue("closed although " + paused.name + " said it paused",
					live.isOpen());
			assertEquals(whilePaused, alice.keys().getEpoch());
			bed.clock.addAndGet(RootEvolutionManager.ANSWER_INTERVAL_MS + 1);
			paused.store.paused = false;
			assertTrue("evolutions resume on the same connection",
					live.await(() -> alice.keys().getEpoch() > whilePaused
							&& RootEvolutionTestBed.sameRoot(alice, bob),
							30_000));
			assertTrue(live.isOpen());
			live.close();
		}
	}

	@Test(timeout = 120_000)
	public void aPeerOfAnEarlierReleaseIsServedAsBefore() throws Exception {
		manager(bob).setEnforcing(false);
		assertTrue(bed.exchange(alice, bob));
		assertEquals(1, alice.keys().getEpoch());
		assertFalse(alice.store.peerEnforces(BOB));
		assertTrue(alice.store.peerEvolves(BOB));
		timings(STAY_MS, LONG_MS);
		bed.passTime();
		int[] before = seen();
		Hold hold = new Hold(KIND_RESP);
		bob.hold = hold;
		Live live = bed.new Live(alice, bob);
		assertTrue(live.await(() -> hold.caught, 20_000));
		assertTrue("records wait for a peer that never promised to evolve",
				live.await(() -> alice.received.size() > before[0]
						&& bob.received.size() > before[1], 20_000));
		Thread.sleep(STAY_MS + 500);
		assertTrue(live.isOpen());
		live.close();
		bob.hold = null;
		assertFalse(alice.store.peerEnforces(BOB));
	}

	@Test(timeout = 120_000)
	public void aPeerThatOnceWaitedIsHeldToItForGood() throws Exception {
		meetOnce();
		manager(bob).setEnforcing(false);
		timings(SHORT_MS, LONG_MS);
		bed.passTime();
		int[] before = seen();
		Hold hold = new Hold(KIND_RESP);
		bob.hold = hold;
		Live live = bed.new Live(alice, bob);
		awaitClosed(live);
		bob.hold = null;
		assertTrue(alice.store.peerEnforces(BOB));
		assertEquals("alice sent although the greeting no longer promised "
				+ "an evolution", before[1], bob.received.size());
	}

	@Test(timeout = 120_000)
	public void aLongConnectionEvolvesAgainAndEndsWhenItCannot()
			throws Exception {
		meetOnce();
		timings(3_000, 600);
		Live live = bed.new Live(alice, bob);
		assertTrue("the open connection evolved again", live.await(
				() -> alice.keys().getEpoch() >= 3
						&& bob.keys().getEpoch() >= 3, 30_000));
		assertTrue(live.isOpen());
		bob.withheld = KIND_RESP;
		awaitClosed(live);
		bob.withheld = 0;
		timings(PATIENT_MS, LONG_MS);
		bed.clock.addAndGet(RootEvolutionManager.ANSWER_INTERVAL_MS + 1);
		assertTrue(bed.exchange(alice, bob));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
	}

	@Test(timeout = 120_000)
	public void anOfferThatCouldNotBeMadeAtFirstIsMadeOnTheSameConnection()
			throws Exception {
		meetOnce();
		bed.passTime();
		int[] before = seen();
		RootEvolution other = new RootEvolution(manager(alice), BOB, true);
		manager(alice).offerStarted(BOB, other);
		Live live = bed.new Live(alice, bob);
		Thread.sleep(700);
		assertTrue(live.isOpen());
		assertEquals(1, alice.keys().getEpoch());
		assertTrue(nothingSince(before));
		manager(alice).offerEnded(BOB, other);
		assertTrue("the evolution starts once the other offer is gone",
				live.await(() -> RootEvolutionTestBed.settled(alice, bob, 1),
						20_000));
		assertTrue(live.await(() -> alice.received.size() > before[0]
				&& bob.received.size() > before[1], 20_000));
		live.close();
		assertEquals(before[2] + 1, alice.connections.get());
	}

	@Test(timeout = 120_000)
	public void anOfferThatGotNoAnswerIsMadeAgainOnTheSameConnection()
			throws Exception {
		meetOnce();
		bed.passTime();
		int[] before = seen();
		alice.withheld = RootEvolutionRecord.KIND_INIT;
		Live live = bed.new Live(alice, bob);
		Thread.sleep(500);
		assertTrue(live.isOpen());
		assertEquals(1, alice.keys().getEpoch());
		assertTrue(nothingSince(before));
		alice.withheld = 0;
		assertTrue("the lost offer is replaced by a new one", live.await(
				() -> RootEvolutionTestBed.settled(alice, bob, 1), 20_000));
		assertTrue(live.await(() -> alice.received.size() > before[0]
				&& bob.received.size() > before[1], 20_000));
		live.close();
		assertEquals(before[2] + 1, alice.connections.get());
	}

	@Test(timeout = 120_000)
	public void theOfferingSideConfirmsAgainWhenTheAnsweringSideStillWaits()
			throws Exception {
		meetOnce();
		bed.passTime();
		Cut cut = new Cut("alice", KIND_CONFIRM);
		alice.cut = cut;
		bed.connect(alice, bob, () -> cut.fired);
		alice.cut = null;
		assertTrue(cut.fired);
		assertTrue(alice.keys().isPendingConfirmed());
		assertEquals(1, bob.keys().getEpoch());
		assertFalse(bob.keys().getPending() == null);
		int[] before = seen();
		alice.withheld = KIND_HELLO;
		Live live = bed.new Live(bob, alice);
		assertTrue("bob learns that alice holds the root he is waiting on",
				live.await(() -> RootEvolutionTestBed.settled(alice, bob, 1),
						20_000));
		assertTrue(live.await(() -> alice.received.size() > before[0]
				&& bob.received.size() > before[1], 20_000));
		live.close();
		alice.withheld = 0;
		assertEquals(2, alice.keys().getEpoch());
		assertEquals(2, bob.keys().getEpoch());
	}

	@Test
	public void aConnectionClosedBesideAnotherOneIsNotCountedAsStalled() {
		RootEvolutionManager m = manager(alice);
		m.evolved(BOB);
		alice.store.setPeerEvolves(BOB);
		bed.passTime();
		RootEvolution first = new RootEvolution(m, BOB, true);
		RootEvolution second = new RootEvolution(m, BOB, true);
		first.start(ZwfControlHandler.eager(p -> {
		}));
		second.start(ZwfControlHandler.eager(p -> {
		}));
		assertTrue(first.holdsUserRecords());
		first.close();
		assertFalse("the redundant connection counts as a stall",
				m.recentlyStalled(ALICE_SEES_BOB));
		second.close();
		assertTrue(m.recentlyStalled(ALICE_SEES_BOB));
		m.evolved(BOB);
		assertFalse(m.recentlyStalled(ALICE_SEES_BOB));
	}

	@Test(timeout = 180_000)
	public void droppingAnswersNeverLetsRecordsFlowUnderTheOldRoot()
			throws Exception {
		meetOnce();
		timings(1_200, LONG_MS);
		bed.passTime();
		int rounds = RootEvolutionManager.MAX_ANSWERS_PER_INTERVAL + 3;
		for (int round = 0; round < rounds; round++) {
			int[] before = seen();
			bob.withheld = KIND_RESP;
			Live live = bed.new Live(alice, bob);
			awaitClosed(live);
			bob.withheld = 0;
			assertTrue("round " + round, nothingSince(before));
			assertEquals(1, alice.keys().getEpoch());
			assertEquals(1, bob.keys().getEpoch());
		}
		timings(PATIENT_MS, LONG_MS);
		bed.clock.addAndGet(RootEvolutionManager.ANSWER_INTERVAL_MS + 1);
		assertTrue(bed.exchange(alice, bob));
		assertEquals(2, alice.keys().getEpoch());
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
	}

	@Test
	public void anEvolutionIsDueTenMinutesAfterTheLastOneOrWhenTheClockWentBack() {
		RootEvolutionManager m = manager(alice);
		assertTrue(m.isDue(BOB));
		m.evolved(BOB);
		assertFalse(m.isDue(BOB));
		assertEquals(bed.clock.get(), alice.store.evolvedAt(BOB));
		assertEquals(LONG_MS, m.untilLiveEvolution(BOB));
		bed.clock.addAndGet(RootEvolutionManager.MIN_EVOLUTION_INTERVAL_MS - 1);
		assertFalse(m.isDue(BOB));
		assertEquals(LONG_MS - RootEvolutionManager.MIN_EVOLUTION_INTERVAL_MS
				+ 1, m.untilLiveEvolution(BOB));
		bed.clock.addAndGet(1);
		assertTrue(m.isDue(BOB));
		m.proved(BOB);
		assertTrue("a root only one side holds does not count", m.isDue(BOB));
		m.evolved(BOB);
		bed.clock.addAndGet(-5_000);
		assertTrue(m.isDue(BOB));
		assertEquals(LONG_MS, m.untilLiveEvolution(BOB));
	}

	@Test
	public void aStalledRenewalIsRememberedForAMinute() {
		RootEvolutionManager m = manager(alice);
		assertFalse(m.recentlyStalled(ALICE_SEES_BOB));
		m.stalled(BOB);
		assertTrue(m.recentlyStalled(ALICE_SEES_BOB));
		assertFalse(m.recentlyStalled(ALICE_SEES_BOB + 1));
		bed.clock.addAndGet(RootEvolutionManager.STALL_MEMORY_MS - 1);
		assertTrue(m.recentlyStalled(ALICE_SEES_BOB));
		bed.clock.addAndGet(1);
		assertFalse(m.recentlyStalled(ALICE_SEES_BOB));
	}

	@Test
	public void theAnswerLimitIsNotJammedByAClockThatWentBack() {
		RootEvolutionManager m = manager(bob);
		for (int i = 0; i < RootEvolutionManager.MAX_ANSWERS_PER_INTERVAL;
				i++) {
			assertTrue(m.mayAnswer(ALICE));
		}
		assertFalse(m.mayAnswer(ALICE));
		bed.clock.addAndGet(-60 * 60_000L);
		assertTrue("answers stamped in the future block the limit",
				m.mayAnswer(ALICE));
		bob.store.snapshotting = true;
		assertFalse(m.mayAnswer(ALICE));
		assertFalse(m.mayOffer(ALICE, new RootEvolution(m, ALICE, true),
				true));
	}

	private static SecretKey copy(SecretKey k) {
		return new SecretKey(k.getBytes().clone());
	}
}
