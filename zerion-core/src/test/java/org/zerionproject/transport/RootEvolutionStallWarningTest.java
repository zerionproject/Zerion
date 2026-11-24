package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.transport.RootEvolutionTestBed.Device;
import org.zerionproject.transport.RootEvolutionTestBed.Hold;
import org.zerionproject.transport.RootEvolutionTestBed.Live;
import org.zerionproject.transport.ZtpConnectionHandlerImpl.SessionConnection;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.transport.RootEvolutionManager.NOT_RENEWING_AFTER_CONNECTIONS;
import static org.zerionproject.transport.RootEvolutionManager.NOT_RENEWING_AFTER_LONG_MS;
import static org.zerionproject.transport.RootEvolutionManager.NOT_RENEWING_AFTER_MS;
import static org.zerionproject.transport.RootEvolutionRecord.FLAG_ENFORCES_EVOLUTION;
import static org.zerionproject.transport.RootEvolutionRecord.FLAG_EVOLUTION_PAUSED;
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
		droppedBeforeTheAnswerArrives(() -> {
		});
	}

	private void droppedBeforeTheAnswerArrives(Runnable whileOpen)
			throws Exception {
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
		assertTrue(live.isOpen());
		whileOpen.run();
		live.close();
		bob.withheld = 0;
		assertEquals("records left on a connection that never evolved",
				aliceBefore, alice.received.size());
		assertEquals(bobBefore, bob.received.size());
	}

	private static ZwfControlHandler.Sender silent() {
		return ZwfControlHandler.eager(p -> {
		});
	}

	private static ZwfControlHandler.Sender closedInto(AtomicBoolean closed) {
		return new ZwfControlHandler.Sender() {
			@Override
			public void send(byte[] payload) {
			}

			@Override
			public void sendWhenDue(Supplier<byte[]> builder) {
			}

			@Override
			public void close() {
				closed.set(true);
			}
		};
	}

	private static void awaitSet(AtomicBoolean flag)
			throws InterruptedException {
		long started = System.currentTimeMillis();
		while (!flag.get() && System.currentTimeMillis() - started < 20_000) {
			Thread.sleep(5);
		}
		assertTrue("the deadline never closed the connection", flag.get());
	}

	private static byte[] greeting(int flags) {
		return RootEvolutionRecord.hello(0,
				new byte[RootEvolutionRecord.MAC_LENGTH], (byte) flags);
	}

	private static SessionConnection session(RootEvolution evolution) {
		return new SessionConnection(new ByteArrayInputStream(new byte[0]),
				new ByteArrayOutputStream(), evolution);
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

	@Test(timeout = 60_000)
	public void aMissingGreetingFromAPeerNotKnownToRenewIsCountedOnlyAtTheDeadline()
			throws Exception {
		RootEvolutionManager m = manager(alice);
		m.evolved(BOB);
		alice.store.setPeerEvolves(BOB);
		assertFalse(alice.store.peerEnforces(BOB));
		bed.passTime();
		alice.store.markOutOfSync(BOB, true);
		RootEvolution early = new RootEvolution(m, BOB, true);
		early.start(silent());
		assertTrue(early.holdsUserRecords());
		early.onPeerStreamAuthenticated(0);
		early.close();
		assertEquals("a connection that ended before the greeting was"
				+ " overdue was counted", 0, alice.store.stalls(BOB)[0]);
		assertTrue("the out of sync mark was cleared without a greeting",
				alice.store.isOutOfSync(BOB));

		m.setTimings(50, LONG_MS);
		AtomicBoolean unauthenticatedClosed = new AtomicBoolean();
		RootEvolution unauthenticated = new RootEvolution(m, BOB, true);
		unauthenticated.start(closedInto(unauthenticatedClosed));
		awaitSet(unauthenticatedClosed);
		unauthenticated.close();
		assertEquals("a connection on which nothing authenticated", 0,
				alice.store.stalls(BOB)[0]);

		AtomicBoolean overdueClosed = new AtomicBoolean();
		RootEvolution overdue = new RootEvolution(m, BOB, true);
		overdue.start(closedInto(overdueClosed));
		assertTrue(overdue.holdsUserRecords());
		overdue.onPeerStreamAuthenticated(0);
		awaitSet(overdueClosed);
		overdue.close();
		assertEquals("a hold that ran to its deadline without a greeting", 1,
				alice.store.stalls(BOB)[0]);
		assertTrue("the out of sync mark was cleared without a greeting",
				alice.store.isOutOfSync(BOB));
		assertTrue(m.recentlyStalled(ALICE_SEES_BOB));
		assertFalse(alice.store.peerEnforces(BOB));
		assertFalse(alice.store.isNotRenewing(BOB));

		m.setTimings(PATIENT_MS, LONG_MS);
		RootEvolution greeted = new RootEvolution(m, BOB, true);
		greeted.start(silent());
		assertTrue(greeted.holdsUserRecords());
		greeted.onRecord(greeting(0));
		assertFalse("a greeting without the promise releases the hold",
				greeted.holdsUserRecords());
		greeted.close();
		assertEquals("a peer of an earlier release that greeted is not"
				+ " counted", 1, alice.store.stalls(BOB)[0]);
		assertFalse("such a connection is in sync as before",
				alice.store.isOutOfSync(BOB));
		assertFalse(alice.store.peerEnforces(BOB));
	}

	@Test
	public void aConnectionThisSideClosedAsRedundantIsNotCountedForAPeerThatRenews() {
		RootEvolutionManager m = manager(alice);
		m.evolved(BOB);
		alice.store.setPeerEvolves(BOB);
		alice.store.setPeerEnforces(BOB, true);
		alice.store.markOutOfSync(BOB, true);
		ZtpConnectionHandlerImpl h = alice.handler;

		RootEvolution redundant = new RootEvolution(m, BOB, true);
		RootEvolution survivor = new RootEvolution(m, BOB, true);
		redundant.start(silent());
		survivor.start(silent());
		assertTrue(h.acquireSession(ALICE_SEES_BOB, TorConstants.ID, false));
		redundant.onPeerStreamAuthenticated(0);
		h.sessionLiveClosingRedundant(ALICE_SEES_BOB, TorConstants.ID, false,
				session(redundant), new AtomicBoolean());
		assertTrue(h.acquireSession(ALICE_SEES_BOB, TorConstants.ID, true));
		survivor.onPeerStreamAuthenticated(0);
		h.sessionLiveClosingRedundant(ALICE_SEES_BOB, TorConstants.ID, true,
				session(survivor), new AtomicBoolean());
		assertTrue(redundant.holdsUserRecords());
		redundant.close();
		assertEquals(0, alice.store.stalls(BOB)[0]);
		survivor.onRecord(greeting(FLAG_ENFORCES_EVOLUTION));
		assertFalse("no evolution is due", survivor.holdsUserRecords());
		survivor.close();
		assertEquals("the connection this side closed as redundant was"
						+ " counted when the surviving one ended", 0,
				alice.store.stalls(BOB)[0]);
		assertFalse(alice.store.isNotRenewing(BOB));
		assertFalse("the surviving connection greeted and is in sync",
				alice.store.isOutOfSync(BOB));
		h.releaseSession(ALICE_SEES_BOB, TorConstants.ID);
		h.releaseSession(ALICE_SEES_BOB, TorConstants.ID);

		alice.store.markOutOfSync(BOB, true);
		RootEvolution alone = new RootEvolution(m, BOB, true);
		alone.start(silent());
		alone.onPeerStreamAuthenticated(0);
		alone.closingAsRedundant();
		alone.close();
		assertEquals(0, alice.store.stalls(BOB)[0]);
		assertTrue("a connection without a greeting cleared the out of sync"
				+ " mark", alice.store.isOutOfSync(BOB));

		RootEvolution dropped = new RootEvolution(m, BOB, true);
		RootEvolution other = new RootEvolution(m, BOB, true);
		dropped.start(silent());
		other.start(silent());
		dropped.onPeerStreamAuthenticated(0);
		other.onPeerStreamAuthenticated(0);
		dropped.close();
		other.onRecord(greeting(FLAG_ENFORCES_EVOLUTION));
		other.close();
		assertEquals("a connection this side did not close is counted as"
				+ " before", 1, alice.store.stalls(BOB)[0]);
	}

	@Test(timeout = 60_000)
	public void aConnectionThisSideClosedAsRedundantIsNotCountedForAPeerNotKnownToRenew()
			throws Exception {
		RootEvolutionManager m = manager(alice);
		m.evolved(BOB);
		alice.store.setPeerEvolves(BOB);
		assertFalse(alice.store.peerEnforces(BOB));
		alice.store.markOutOfSync(BOB, true);
		m.setTimings(50, LONG_MS);
		ZtpConnectionHandlerImpl h = alice.handler;

		AtomicBoolean redundantClosed = new AtomicBoolean();
		RootEvolution redundant = new RootEvolution(m, BOB, true);
		RootEvolution survivor = new RootEvolution(m, BOB, true);
		redundant.start(closedInto(redundantClosed));
		survivor.start(silent());
		assertTrue(h.acquireSession(ALICE_SEES_BOB, TorConstants.ID, true));
		survivor.onPeerStreamAuthenticated(0);
		h.sessionLiveClosingRedundant(ALICE_SEES_BOB, TorConstants.ID, true,
				session(survivor), new AtomicBoolean());
		survivor.onRecord(greeting(0));
		assertTrue(h.acquireSession(ALICE_SEES_BOB, TorConstants.ID, true));
		awaitSet(redundantClosed);
		AtomicBoolean counted = new AtomicBoolean();
		h.sessionLiveClosingRedundant(ALICE_SEES_BOB, TorConstants.ID, false,
				session(redundant), counted);
		assertFalse("the other side's session stayed beside the dialler's",
				counted.get());
		redundant.onPeerStreamAuthenticated(0);
		redundant.close();
		survivor.close();
		assertEquals("the connection this side closed as redundant was"
						+ " counted when the surviving one ended", 0,
				alice.store.stalls(BOB)[0]);
		assertFalse(alice.store.isOutOfSync(BOB));
		h.releaseSession(ALICE_SEES_BOB, TorConstants.ID);
		h.releaseSession(ALICE_SEES_BOB, TorConstants.ID);

		alice.store.markOutOfSync(BOB, true);
		AtomicBoolean aloneClosed = new AtomicBoolean();
		RootEvolution alone = new RootEvolution(m, BOB, true);
		alone.start(closedInto(aloneClosed));
		alone.onPeerStreamAuthenticated(0);
		awaitSet(aloneClosed);
		alone.closingAsRedundant();
		alone.close();
		assertEquals(0, alice.store.stalls(BOB)[0]);
		assertTrue("a connection without a greeting cleared the out of sync"
				+ " mark", alice.store.isOutOfSync(BOB));

		AtomicBoolean overdueClosed = new AtomicBoolean();
		RootEvolution overdue = new RootEvolution(m, BOB, true);
		RootEvolution other = new RootEvolution(m, BOB, true);
		overdue.start(closedInto(overdueClosed));
		other.start(silent());
		other.onRecord(greeting(0));
		overdue.onPeerStreamAuthenticated(0);
		awaitSet(overdueClosed);
		overdue.close();
		other.close();
		assertEquals("a hold that ran to its deadline on a connection this"
						+ " side did not close is counted", 1,
				alice.store.stalls(BOB)[0]);
	}

	@Test
	public void aRunDueByTimeRaisesNothingWhenAConnectionStartsOrThePeerIsPaused() {
		RootEvolutionManager m = manager(alice);
		m.evolved(BOB);
		alice.store.setPeerEvolves(BOB);
		alice.store.setPeerEnforces(BOB, true);
		long firstStalledAt = bed.clock.get();
		bed.clock.addAndGet(NOT_RENEWING_AFTER_LONG_MS);
		for (int count = 2; count < NOT_RENEWING_AFTER_CONNECTIONS; count++) {
			alice.store.setStalls(BOB, count, firstStalledAt);
			RootEvolution started = new RootEvolution(m, BOB, true);
			started.start(silent());
			assertTrue(started.holdsUserRecords());
			assertFalse("the notice appeared when a connection started",
					alice.store.isNotRenewing(BOB));
			started.onPeerStreamAuthenticated(0);
			started.onRecord(greeting(FLAG_ENFORCES_EVOLUTION
					| FLAG_EVOLUTION_PAUSED));
			assertFalse("a paused peer is not waited for",
					started.holdsUserRecords());
			started.close();
			assertEquals("a paused peer was counted", count,
					alice.store.stalls(BOB)[0]);
			assertEquals(firstStalledAt, alice.store.stalls(BOB)[1]);
		}
		assertFalse(alice.store.isNotRenewing(BOB));
		assertEquals(0, alice.store.notRenewingRaised.get());
	}

	@Test(timeout = 120_000)
	public void aConnectionWhoseGreetingNeverArrivesIsCountedByTheDeadline()
			throws Exception {
		meetOnce();
		manager(alice).setTimings(1_500, LONG_MS);
		manager(bob).setTimings(1_500, LONG_MS);
		bed.passTime();
		alice.store.markOutOfSync(BOB, true);
		bob.withheld = KIND_HELLO;
		Live live = bed.new Live(alice, bob);
		long opened = System.currentTimeMillis();
		while (live.isOpen()
				&& System.currentTimeMillis() - opened < 30_000) {
			Thread.sleep(20);
		}
		assertFalse("the connection was not closed", live.isOpen());
		live.close();
		bob.withheld = 0;
		assertEquals(1, alice.store.stalls(BOB)[0]);
		assertTrue(alice.store.isOutOfSync(BOB));
		assertEquals("bob waited for an evolution that never came", 1,
				bob.store.stalls(ALICE)[0]);
	}

	@Test(timeout = 180_000)
	public void twoStallsAtOnceRaiseTheNoticeOnlyWhenAThirdIsCountedADayLater()
			throws Exception {
		meetOnce();
		bed.passTime();
		droppedBeforeTheAnswerArrives();
		droppedBeforeTheAnswerArrives();
		assertEquals(2, alice.store.stalls(BOB)[0]);
		assertEquals(2, bob.store.stalls(ALICE)[0]);
		bed.clock.addAndGet(NOT_RENEWING_AFTER_LONG_MS);
		droppedBeforeTheAnswerArrives(() -> {
			assertFalse("the notice appeared when the connection started",
					alice.store.isNotRenewing(BOB));
			assertFalse(bob.store.isNotRenewing(ALICE));
			assertEquals(2, alice.store.stalls(BOB)[0]);
			assertEquals(2, bob.store.stalls(ALICE)[0]);
		});
		assertEquals(3, alice.store.stalls(BOB)[0]);
		assertEquals(3, bob.store.stalls(ALICE)[0]);
		assertTrue(alice.store.isNotRenewing(BOB));
		assertTrue(bob.store.isNotRenewing(ALICE));
		bed.passTime();
		assertTrue(bed.exchange(alice, bob));
		assertEquals(2, alice.keys().getEpoch());
		assertFalse(alice.store.isNotRenewing(BOB));
		assertFalse(bob.store.isNotRenewing(ALICE));
		assertEquals(0, alice.store.stalls(BOB)[0]);
	}

	@Test(timeout = 180_000)
	public void twoStallsADayApartRaiseTheNoticeWhenTheSecondIsCounted()
			throws Exception {
		meetOnce();
		bed.passTime();
		droppedBeforeTheAnswerArrives();
		bed.clock.addAndGet(NOT_RENEWING_AFTER_LONG_MS);
		droppedBeforeTheAnswerArrives(() -> {
			assertFalse("the notice appeared when the connection started",
					alice.store.isNotRenewing(BOB));
			assertFalse(bob.store.isNotRenewing(ALICE));
		});
		assertEquals(2, alice.store.stalls(BOB)[0]);
		assertEquals(2, bob.store.stalls(ALICE)[0]);
		assertTrue(alice.store.isNotRenewing(BOB));
		assertTrue(bob.store.isNotRenewing(ALICE));
	}

	@Test(timeout = 180_000)
	public void aRenewalThatCompletesADayAfterTwoStallsRaisesNothing()
			throws Exception {
		meetOnce();
		bed.passTime();
		droppedBeforeTheAnswerArrives();
		droppedBeforeTheAnswerArrives();
		bed.clock.addAndGet(NOT_RENEWING_AFTER_LONG_MS);
		assertTrue(bed.exchange(alice, bob));
		assertEquals(2, alice.keys().getEpoch());
		assertEquals("the notice was raised on a connection that renewed", 0,
				alice.store.notRenewingRaised.get());
		assertEquals(0, bob.store.notRenewingRaised.get());
		assertEquals(0, alice.store.stalls(BOB)[0]);
		assertEquals(0, bob.store.stalls(ALICE)[0]);
	}

	@Test(timeout = 180_000)
	public void aPausedPeerIsNeitherCountedNorRaisesTheNotice()
			throws Exception {
		meetOnce();
		bed.passTime();
		droppedBeforeTheAnswerArrives();
		droppedBeforeTheAnswerArrives();
		bed.clock.addAndGet(NOT_RENEWING_AFTER_LONG_MS);
		bob.store.paused = true;
		for (int i = 0; i < 3; i++) {
			assertTrue("records wait for a paused side",
					bed.exchange(alice, bob));
			assertEquals(2, alice.store.stalls(BOB)[0]);
			assertEquals(2, bob.store.stalls(ALICE)[0]);
		}
		bob.store.paused = false;
		assertEquals(1, alice.keys().getEpoch());
		assertEquals("the notice was raised while the peer was paused", 0,
				alice.store.notRenewingRaised.get());
		assertEquals(0, bob.store.notRenewingRaised.get());
	}

	@Test(timeout = 180_000)
	public void renewalsThatCompleteNeverRaiseTheNoticeHoweverFarApart()
			throws Exception {
		meetOnce();
		for (int day = 0; day < 3; day++) {
			bed.clock.addAndGet(NOT_RENEWING_AFTER_LONG_MS);
			droppedBeforeTheAnswerArrives();
			assertEquals(1, alice.store.stalls(BOB)[0]);
			assertFalse(alice.store.isNotRenewing(BOB));
			assertTrue(bed.exchange(alice, bob));
			assertEquals(0, alice.store.stalls(BOB)[0]);
			assertEquals(0, bob.store.stalls(ALICE)[0]);
			assertFalse(alice.store.isNotRenewing(BOB));
			assertFalse(bob.store.isNotRenewing(ALICE));
		}
		assertEquals(4, alice.keys().getEpoch());
	}

	private static SecretKey copy(SecretKey k) {
		return new SecretKey(k.getBytes().clone());
	}
}
