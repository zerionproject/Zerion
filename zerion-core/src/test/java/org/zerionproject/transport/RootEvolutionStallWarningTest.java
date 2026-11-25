package org.zerionproject.transport;

import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.PluginConfig;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexPluginFactory;
import org.zerionproject.core.api.plugin.event.TransportInactiveEvent;
import org.zerionproject.core.api.plugin.simplex.SimplexPluginFactory;
import org.zerionproject.core.api.sync.Priority;
import org.zerionproject.transport.RootEvolutionTestBed.Device;
import org.zerionproject.transport.RootEvolutionTestBed.Hold;
import org.zerionproject.transport.RootEvolutionTestBed.Live;
import org.zerionproject.transport.ZtpConnectionHandlerImpl.SessionConnection;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.transport.RootEvolutionManager.NOT_RENEWING_AFTER_CONNECTIONS;
import static org.zerionproject.transport.RootEvolutionManager.NOT_RENEWING_AFTER_LONG_MS;
import static org.zerionproject.transport.RootEvolutionManager.NOT_RENEWING_AFTER_MS;
import static org.zerionproject.transport.RootEvolutionManager.PEER_PAUSE_MEMORY_MS;
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

	private static SessionConnection session(RootEvolution evolution,
			AtomicBoolean closed) {
		return new SessionConnection(new ByteArrayInputStream(new byte[0]),
				new ByteArrayOutputStream() {
					@Override
					public void close() throws IOException {
						closed.set(true);
						super.close();
					}
				}, evolution);
	}

	private static ConnectionRegistry registry() throws Exception {
		Constructor<?> c = Class.forName(
				"org.zerionproject.core.connection.ConnectionRegistryImpl")
				.getDeclaredConstructor(EventBus.class, PluginConfig.class);
		c.setAccessible(true);
		return (ConnectionRegistry) c.newInstance(new EventBus() {
			@Override
			public void addListener(EventListener l) {
			}

			@Override
			public void removeListener(EventListener l) {
			}

			@Override
			public void broadcast(Event e) {
			}
		}, new PluginConfig() {
			@Override
			public Collection<DuplexPluginFactory> getDuplexFactories() {
				return Collections.emptyList();
			}

			@Override
			public Collection<SimplexPluginFactory> getSimplexFactories() {
				return Collections.emptyList();
			}

			@Override
			public boolean shouldPoll() {
				return false;
			}

			@Override
			public Map<TransportId, List<TransportId>>
					getTransportPreferences() {
				return Collections.emptyMap();
			}
		});
	}

	private static Priority priority(int fill) {
		byte[] nonce = new byte[16];
		Arrays.fill(nonce, (byte) fill);
		return new Priority(nonce);
	}

	private RootEvolutionManager aliceKnowsThatBobRenews() {
		RootEvolutionManager m = manager(alice);
		m.evolved(BOB);
		alice.store.setPeerEvolves(BOB);
		alice.store.setPeerEnforces(BOB, true);
		return m;
	}

	private static void greetedAndEnded(RootEvolutionManager m, int flags) {
		RootEvolution greeted = new RootEvolution(m, BOB, true);
		greeted.start(silent());
		greeted.onPeerStreamAuthenticated(0);
		greeted.onRecord(greeting(flags));
		greeted.close();
	}

	private static void endedBeforeTheGreeting(RootEvolutionManager m) {
		RootEvolution ungreeted = new RootEvolution(m, BOB, true);
		ungreeted.start(silent());
		ungreeted.onPeerStreamAuthenticated(0);
		ungreeted.close();
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

	@Test
	public void aPeerThatSaidItIsPausedIsNotCountedWhenLaterConnectionsEndBeforeItsGreeting() {
		RootEvolutionManager m = aliceKnowsThatBobRenews();
		greetedAndEnded(m, FLAG_ENFORCES_EVOLUTION | FLAG_EVOLUTION_PAUSED);
		alice.store.markOutOfSync(BOB, true);
		for (int i = 1; i <= 3; i++) {
			bed.clock.addAndGet(NOT_RENEWING_AFTER_MS / 2 + 1);
			endedBeforeTheGreeting(m);
			assertEquals("connection " + i + " to a paused peer was counted",
					0, alice.store.stalls(BOB)[0]);
			assertTrue("a connection without a greeting cleared the out of"
					+ " sync mark", alice.store.isOutOfSync(BOB));
		}
		assertFalse(alice.store.isNotRenewing(BOB));
		assertEquals(0, alice.store.notRenewingRaised.get());

		alice.store.renewed(BOB, bed.clock.get());
		greetedAndEnded(m, FLAG_ENFORCES_EVOLUTION);
		assertEquals("a greeting on which no renewal was due was counted", 0,
				alice.store.stalls(BOB)[0]);
		endedBeforeTheGreeting(m);
		assertEquals("a peer that said it is no longer paused was still"
				+ " excused", 1, alice.store.stalls(BOB)[0]);
	}

	@Test
	public void anOfferFromThePeerEndsWhatIsRememberedOfItsPause() {
		RootEvolutionManager m = manager(bob);
		bob.store.setPeerEvolves(ALICE);
		bob.store.setPeerEnforces(ALICE, true);
		RootEvolution paused = new RootEvolution(m, ALICE, false);
		paused.start(silent());
		paused.onPeerStreamAuthenticated(0);
		paused.onRecord(greeting(
				FLAG_ENFORCES_EVOLUTION | FLAG_EVOLUTION_PAUSED));
		paused.close();
		RootEvolution dropped = new RootEvolution(m, ALICE, false);
		dropped.start(silent());
		dropped.onPeerStreamAuthenticated(0);
		dropped.close();
		assertEquals("a connection to a paused peer was counted", 0,
				bob.store.stalls(ALICE)[0]);
		assertEquals(1, m.peerPausesRemembered());

		RootEvolution offered = new RootEvolution(m, ALICE, false);
		offered.start(silent());
		offered.onPeerStreamAuthenticated(0);
		offered.onRecord(greeting(
				FLAG_ENFORCES_EVOLUTION | FLAG_EVOLUTION_PAUSED));
		assertEquals(1, m.peerPausesRemembered());
		offered.onRecord(RootEvolutionRecord.init(0,
				new byte[RootEvolutionRecord.MAC_LENGTH],
				bed.crypto.generateAgreementKeyPair().getPublic().getEncoded(),
				bed.mlKem.generateKeyPair().getEncapsulationKey()));
		assertTrue("the offer was not answered",
				bob.keys().getPending() != null);
		assertEquals("an offer from the peer left its pause remembered", 0,
				m.peerPausesRemembered());
		offered.close();
		assertEquals(0, bob.store.stalls(ALICE)[0]);

		RootEvolution next = new RootEvolution(m, ALICE, false);
		next.start(silent());
		next.onPeerStreamAuthenticated(0);
		next.close();
		assertEquals("a peer that offered was still excused as paused", 1,
				bob.store.stalls(ALICE)[0]);
	}

	@Test
	public void aPeersPauseIsRememberedForTheLengthOfAPauseAndNoLonger() {
		RootEvolutionManager m = aliceKnowsThatBobRenews();
		greetedAndEnded(m, FLAG_ENFORCES_EVOLUTION | FLAG_EVOLUTION_PAUSED);
		bed.clock.addAndGet(PEER_PAUSE_MEMORY_MS - 1);
		endedBeforeTheGreeting(m);
		assertEquals(0, alice.store.stalls(BOB)[0]);
		assertEquals(1, m.peerPausesRemembered());
		bed.clock.addAndGet(1);
		endedBeforeTheGreeting(m);
		assertEquals("a peer was excused after the length of a pause", 1,
				alice.store.stalls(BOB)[0]);
		assertEquals("a pause that is over stayed in memory", 0,
				m.peerPausesRemembered());
		endedBeforeTheGreeting(m);
		assertEquals(2, alice.store.stalls(BOB)[0]);
	}

	@Test
	public void aClockThatGoesBackEndsWhatIsRememberedOfAPeersPause() {
		RootEvolutionManager m = aliceKnowsThatBobRenews();
		greetedAndEnded(m, FLAG_ENFORCES_EVOLUTION | FLAG_EVOLUTION_PAUSED);
		bed.clock.addAndGet(-LONG_MS);
		endedBeforeTheGreeting(m);
		assertEquals("a clock set back to before the greeting kept the peer"
				+ " excused", 1, alice.store.stalls(BOB)[0]);
		assertEquals(0, m.peerPausesRemembered());

		greetedAndEnded(m, FLAG_ENFORCES_EVOLUTION | FLAG_EVOLUTION_PAUSED);
		bed.clock.addAndGet(10 * LONG_MS);
		endedBeforeTheGreeting(m);
		assertEquals(1, alice.store.stalls(BOB)[0]);
		bed.clock.addAndGet(-5 * LONG_MS);
		endedBeforeTheGreeting(m);
		assertEquals("a clock set back after the greeting made the peer's"
				+ " pause last longer", 2, alice.store.stalls(BOB)[0]);
		assertEquals(0, m.peerPausesRemembered());
	}

	@Test
	public void aConnectionThatEndedBesideOneOnWhichThePeerSaysItIsPausedIsNotCounted() {
		RootEvolutionManager m = aliceKnowsThatBobRenews();
		bed.passTime();
		RootEvolution dropped = new RootEvolution(m, BOB, true);
		RootEvolution other = new RootEvolution(m, BOB, true);
		dropped.start(silent());
		other.start(silent());
		dropped.onPeerStreamAuthenticated(0);
		other.onPeerStreamAuthenticated(0);
		dropped.close();
		assertEquals("a pause was known before the peer said so", 0,
				m.peerPausesRemembered());
		assertEquals(0, alice.store.stalls(BOB)[0]);
		other.onRecord(greeting(FLAG_ENFORCES_EVOLUTION
				| FLAG_EVOLUTION_PAUSED));
		assertFalse("a paused peer is not waited for",
				other.holdsUserRecords());
		other.close();
		assertEquals("a connection that ended before the greeting, beside"
						+ " the one on which the peer said it is paused, was"
						+ " counted", 0, alice.store.stalls(BOB)[0]);
		assertFalse(alice.store.isNotRenewing(BOB));
		endedBeforeTheGreeting(m);
		assertEquals(0, alice.store.stalls(BOB)[0]);
	}

	@Test
	public void theTimeAPeerWasPausedDoesNotCountTowardTheDayBetweenTwoStalls() {
		RootEvolutionManager m = aliceKnowsThatBobRenews();
		bed.passTime();
		endedBeforeTheGreeting(m);
		assertEquals(1, alice.store.stalls(BOB)[0]);
		long firstStalledAt = alice.store.stalls(BOB)[1];
		for (int i = 0; i < 4; i++) {
			greetedAndEnded(m, FLAG_ENFORCES_EVOLUTION
					| FLAG_EVOLUTION_PAUSED);
			bed.clock.addAndGet(NOT_RENEWING_AFTER_LONG_MS / 4);
			endedBeforeTheGreeting(m);
		}
		assertEquals("a paused peer was counted", 1,
				alice.store.stalls(BOB)[0]);
		assertEquals(firstStalledAt, alice.store.stalls(BOB)[1]);
		long pauseEndedAt = bed.clock.get();
		assertTrue(pauseEndedAt - firstStalledAt
				>= NOT_RENEWING_AFTER_LONG_MS);
		greetedAndEnded(m, FLAG_ENFORCES_EVOLUTION);
		assertEquals("the renewal that was due after the pause and did not"
				+ " complete was not counted", 2, alice.store.stalls(BOB)[0]);
		assertEquals("the time of the run did not start again when the"
				+ " pause ended", pauseEndedAt, alice.store.stalls(BOB)[1]);
		assertFalse("the time the peer was paused counted toward the day"
				+ " between two stalls", alice.store.isNotRenewing(BOB));
		assertEquals(0, alice.store.notRenewingRaised.get());
		bed.clock.addAndGet(NOT_RENEWING_AFTER_LONG_MS);
		endedBeforeTheGreeting(m);
		assertEquals(3, alice.store.stalls(BOB)[0]);
		assertTrue("a day without a pause did not count",
				alice.store.isNotRenewing(BOB));
	}

	@Test
	public void aPausedSideClearsTheOutOfSyncMarkOnlyOnThePeersGreeting() {
		RootEvolutionManager m = manager(alice);
		m.evolved(BOB);
		alice.store.setPeerEvolves(BOB);
		bed.passTime();
		alice.store.paused = true;
		for (boolean renews : new boolean[] {false, true}) {
			if (renews) alice.store.setPeerEnforces(BOB, true);
			alice.store.markOutOfSync(BOB, true);
			RootEvolution ungreeted = new RootEvolution(m, BOB, true);
			ungreeted.start(silent());
			assertFalse("a paused side waited for the greeting",
					ungreeted.holdsUserRecords());
			ungreeted.onPeerStreamAuthenticated(0);
			ungreeted.close();
			assertTrue("a paused side cleared the out of sync mark without"
					+ " the peer's greeting", alice.store.isOutOfSync(BOB));
			assertEquals("a paused side counted a connection", 0,
					alice.store.stalls(BOB)[0]);
			greetedAndEnded(m, renews ? FLAG_ENFORCES_EVOLUTION : 0);
			assertFalse("a paused side that got the peer's greeting is in"
					+ " sync", alice.store.isOutOfSync(BOB));
			assertEquals("a paused side counted a connection", 0,
					alice.store.stalls(BOB)[0]);
		}
		assertFalse(alice.store.isNotRenewing(BOB));
	}

	@Test
	public void aContactThatNeverGreetedIsInSyncOnceItsStreamAuthenticates() {
		RootEvolutionManager m = manager(alice);
		assertEquals(0, alice.keys().getEpoch());
		for (boolean paused : new boolean[] {false, true}) {
			alice.store.paused = paused;
			alice.store.markOutOfSync(BOB, true);
			RootEvolution unauthenticated = new RootEvolution(m, BOB, true);
			unauthenticated.start(silent());
			assertFalse(unauthenticated.holdsUserRecords());
			unauthenticated.close();
			assertTrue("a connection on which nothing authenticated cleared"
					+ " the out of sync mark", alice.store.isOutOfSync(BOB));
			endedBeforeTheGreeting(m);
			assertFalse("with a contact that never greeted, a connection"
							+ " that authenticates is all there is",
					alice.store.isOutOfSync(BOB));
			assertEquals(0, alice.store.stalls(BOB)[0]);
		}
		assertFalse(alice.store.peerEvolves(BOB));
		assertFalse(alice.store.peerEnforces(BOB));
	}

	@Test
	public void theLowerOfTwoDialsClosedByTheRegistryIsNotCountedForAPeerThatRenews()
			throws Exception {
		RootEvolutionManager m = aliceKnowsThatBobRenews();
		for (boolean lowerFirst : new boolean[] {true, false}) {
			ConnectionRegistry registry = registry();
			AtomicBoolean lowerClosed = new AtomicBoolean();
			AtomicBoolean survivorClosed = new AtomicBoolean();
			RootEvolution lower = new RootEvolution(m, BOB, true);
			RootEvolution survivor = new RootEvolution(m, BOB, true);
			SessionConnection lowerSession = session(lower, lowerClosed);
			SessionConnection survivorSession =
					session(survivor, survivorClosed);
			lower.start(silent());
			survivor.start(silent());
			lower.onPeerStreamAuthenticated(0);
			survivor.onPeerStreamAuthenticated(0);
			if (lowerFirst) {
				registry.registerOutgoingConnection(BOB, TorConstants.ID,
						lowerSession, priority(1));
			}
			registry.registerOutgoingConnection(BOB, TorConstants.ID,
					survivorSession, priority(2));
			if (!lowerFirst) {
				registry.registerOutgoingConnection(BOB, TorConstants.ID,
						lowerSession, priority(1));
			}
			assertTrue("the registry kept the lower of two dials",
					lowerClosed.get());
			assertFalse(survivorClosed.get());
			lower.close();
			registry.unregisterConnection(BOB, TorConstants.ID, lowerSession,
					false, false);
			survivor.onRecord(greeting(FLAG_ENFORCES_EVOLUTION));
			assertFalse("no evolution is due", survivor.holdsUserRecords());
			survivor.close();
			registry.unregisterConnection(BOB, TorConstants.ID,
					survivorSession, false, false);
			assertEquals("the dial the registry closed as the lower of two"
							+ " was counted when the surviving one ended", 0,
					alice.store.stalls(BOB)[0]);
		}
		assertFalse(alice.store.isNotRenewing(BOB));
	}

	@Test
	public void aConnectionTheRegistryClosedWithItsTransportIsCountedAsBefore()
			throws Exception {
		RootEvolutionManager m = aliceKnowsThatBobRenews();
		ConnectionRegistry registry = registry();
		AtomicBoolean closed = new AtomicBoolean();
		RootEvolution only = new RootEvolution(m, BOB, true);
		SessionConnection session = session(only, closed);
		only.start(silent());
		only.onPeerStreamAuthenticated(0);
		registry.registerOutgoingConnection(BOB, TorConstants.ID, session,
				priority(1));
		assertFalse(closed.get());
		((EventListener) registry).eventOccurred(
				new TransportInactiveEvent(TorConstants.ID));
		assertTrue(closed.get());
		only.close();
		assertEquals("a connection closed for another reason than being"
						+ " redundant was not counted", 1,
				alice.store.stalls(BOB)[0]);
	}

	private static SecretKey copy(SecretKey k) {
		return new SecretKey(k.getBytes().clone());
	}
}
