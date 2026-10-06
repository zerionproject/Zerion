package org.zerionproject.transport;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.transport.RootEvolutionTestBed.Cut;
import org.zerionproject.transport.RootEvolutionTestBed.Device;
import org.zerionproject.transport.RootEvolutionTestBed.Hold;
import org.zerionproject.transport.RootEvolutionTestBed.Live;
import org.junit.Before;
import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_CONFIRM;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_DONE;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_HELLO;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_INIT;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_RESP;

public class RootEvolutionInterleavingTest {

	private static final int ALICE_SEES_BOB = 2;
	private static final int BOB_SEES_ALICE = 1;

	private RootEvolutionTestBed bed;
	private SecretKey pairingRoot;

	@Before
	public void setUp() throws Exception {
		bed = new RootEvolutionTestBed();
		pairingRoot = bed.randomKey();
	}

	private Device alice() {
		return bed.new Device("alice", ALICE_SEES_BOB, true,
				ContactRootKeys.atPairing(copy(pairingRoot)), true);
	}

	private Device bob() {
		return bed.new Device("bob", BOB_SEES_ALICE, false,
				ContactRootKeys.atPairing(copy(pairingRoot)), true);
	}

	@Test(timeout = 120_000)
	public void aHelloFromALaggedParallelConnectionNeverStrandsTheRoots()
			throws Exception {
		Device alice = alice();
		Device bob = bob();
		Hold hold = new Hold(KIND_RESP);
		bob.hold = hold;
		Live first = bed.new Live(alice, bob);
		assertTrue("bob answers", first.await(() -> hold.caught, 20_000));
		assertEquals(0, bob.keys().getEpoch());
		assertFalse(bob.keys().getPending() == null);
		Live second = bed.new Live(alice, bob);
		assertTrue("alice's second HELLO reaches bob", second.await(
				() -> count(bob, KIND_HELLO) >= 2
						&& count(alice, KIND_HELLO) >= 2, 20_000));
		Thread.sleep(200);
		bob.hold = null;
		hold.release();
		first.await(() -> RootEvolutionTestBed.settled(alice, bob, 0),
				5_000);
		first.close();
		second.close();
		for (int i = 0; i < 3; i++) {
			bed.passTime();
			assertTrue("alice dials " + i, bed.exchange(alice, bob));
		}
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertTrue(alice.keys().getEpoch() >= 1);
	}

	@Test(timeout = 120_000)
	public void aReplayedHelloFromARootHolderNeitherStallsNorCutsOffThePair()
			throws Exception {
		Device alice = alice();
		Device bob = bob();
		Device mallory = bed.new Device("mallory", ALICE_SEES_BOB, true,
				ContactRootKeys.atPairing(copy(pairingRoot)), true,
				new RootEvolutionTestBed.ReplayingManager(bed));
		for (int i = 0; i < 4; i++) {
			mallory.counter.allocateSendStreamId(ALICE_SEES_BOB,
					mallory.counter.generation(ALICE_SEES_BOB));
		}
		Hold hold = new Hold(KIND_RESP);
		bob.hold = hold;
		Live first = bed.new Live(alice, bob);
		assertTrue(first.await(() -> hold.caught, 20_000));
		int bobBefore = bob.received.size();
		Live replay = bed.new Live(mallory, bob);
		assertTrue("mallory's HELLO reaches bob", replay.await(
				() -> count(bob, KIND_HELLO) >= 2
						&& bob.received.size() > bobBefore, 20_000));
		Thread.sleep(200);
		replay.close();
		bob.hold = null;
		hold.release();
		first.await(() -> RootEvolutionTestBed.settled(alice, bob, 0),
				5_000);
		first.close();
		for (int i = 0; i < 2; i++) {
			bed.passTime();
			assertTrue("alice dials " + i, bed.exchange(alice, bob));
		}
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertTrue(alice.keys().getEpoch() >= 1);
		int bobNow = bob.received.size();
		RootEvolutionTestBed.Outcome o = bed.connect(mallory, bob,
				() -> bob.received.size() > bobNow);
		assertTrue(RootEvolutionTestBed.isFormatException(o.acceptorError));
	}

	@Test(timeout = 180_000)
	public void aRootTheResponderLostIsRecoveredByTheDesignatedDialler()
			throws Exception {
		Device alice = alice();
		Device bob = bob();
		Cut cut = new Cut("alice", KIND_CONFIRM);
		alice.cut = cut;
		bed.connect(alice, bob, () -> cut.fired);
		assertTrue(cut.fired);
		alice.cut = null;
		assertTrue(alice.keys().isPendingConfirmed());
		bob.resetKeys(ContactRootKeys.atPairing(copy(pairingRoot)));
		int flowed = 0;
		for (int i = 0; i < 6; i++) {
			bed.passTime();
			if (bed.exchange(alice, bob)) flowed++;
			if (flowed >= 2) break;
		}
		assertTrue("alice's dials reach bob again", flowed >= 2);
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertTrue(alice.keys().getEpoch() >= 1);
		assertNull(alice.keys().getPending());
	}

	@Test(timeout = 120_000)
	public void aFallbackDialUnderTheOldRootDoesNotLockTheDiallerOut()
			throws Exception {
		Device alice = alice();
		Device bob = bob();
		Cut cut = new Cut("bob", KIND_DONE);
		bob.cut = cut;
		bed.connect(alice, bob, () -> cut.fired);
		assertTrue(cut.fired);
		bob.cut = null;
		assertEquals(1, bob.keys().getEpoch());
		assertTrue(alice.keys().isPendingConfirmed());
		RootEvolutionManager m = alice.evolutions;
		if (m == null) throw new AssertionError();
		ContactId c = new ContactId(ALICE_SEES_BOB);
		for (int i = 0; i < RootEvolutionManager.PENDING_DIAL_FALLBACK_AFTER;
				i++) {
			m.dialEnded(c, false);
		}
		assertEquals(0, m.dialEpoch(c, alice.keys()));
		boolean first = bed.exchange(alice, bob);
		bed.passTime();
		boolean second = bed.exchange(alice, bob);
		assertTrue(first || second);
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertEquals(1, alice.keys().getEpoch());
	}

	@Test(timeout = 300_000)
	public void anEvolutionCutOffAtAnyStepNeverStopsTheDesignatedDialler()
			throws Exception {
		byte[] kinds = {KIND_HELLO, KIND_INIT, KIND_RESP, KIND_CONFIRM,
				KIND_DONE};
		for (String who : new String[] {"alice", "bob"}) {
			for (byte kind : kinds) {
				setUp();
				Device alice = alice();
				Device bob = bob();
				String where = who + " kind " + kind;
				Device cutter = who.equals("alice") ? alice : bob;
				Cut cut = new Cut(who, kind);
				cutter.cut = cut;
				bed.connect(alice, bob, () -> cut.fired);
				if (!cut.fired) continue;
				cutter.cut = null;
				for (int i = 0; i < 3; i++) {
					bed.passTime();
					assertTrue(where + " alice dials " + i,
							bed.exchange(alice, bob));
				}
				assertTrue(where, RootEvolutionTestBed.sameRoot(alice, bob));
				assertTrue(where, alice.keys().getEpoch() >= 1);
			}
		}
	}

	@Test(timeout = 600_000)
	public void randomInterleavingsAlwaysLeaveTheDesignatedDiallerAbleToConnect()
			throws Exception {
		byte[] kinds = {KIND_HELLO, KIND_INIT, KIND_RESP, KIND_CONFIRM,
				KIND_DONE};
		for (int seed = 1; seed <= 8; seed++) {
			Random random = new Random(seed);
			setUp();
			Device alice = alice();
			Device bob = bob();
			String where = "seed " + seed;
			int rounds = 2 + random.nextInt(2);
			for (int r = 0; r < rounds; r++) {
				int action = random.nextInt(4);
				Device cutter = random.nextBoolean() ? alice : bob;
				byte kind = kinds[random.nextInt(kinds.length)];
				if (action == 0) {
					Cut cut = new Cut(cutter.name, kind);
					cutter.cut = cut;
					bed.connect(alice, bob, () -> cut.fired);
					cutter.cut = null;
				} else if (action == 1) {
					Cut cut = new Cut(cutter.name, kind);
					cutter.cut = cut;
					try {
						bed.parallel(alice, bob);
					} catch (AssertionError ignored) {
					}
					cutter.cut = null;
				} else if (action == 2) {
					Hold hold = new Hold(KIND_RESP);
					bob.hold = hold;
					Live first = bed.new Live(alice, bob);
					first.await(() -> hold.caught, 5_000);
					Live second = bed.new Live(alice, bob);
					second.await(() -> count(bob, KIND_HELLO) >= 2, 5_000);
					bob.hold = null;
					if (random.nextBoolean()) {
						first.close();
						hold.release();
					} else {
						hold.release();
						first.await(() -> RootEvolutionTestBed.settled(alice,
								bob, 0), 3_000);
						first.close();
					}
					second.close();
				} else {
					bed.exchange(alice, bob);
				}
				if (random.nextInt(5) == 0) {
					ContactRootKeys k = bob.keys();
					if (k.getPending() != null) {
						bob.resetKeys(new ContactRootKeys(k.getEpoch(),
								copy(k.getCurrent()), null, false));
					}
				}
				bed.passTime();
			}
			int flowed = 0;
			for (int i = 0; i < 8 && flowed < 2; i++) {
				bed.passTime();
				if (bed.exchange(alice, bob)) flowed++;
				else flowed = 0;
			}
			assertTrue(where + " two dials in a row carry data", flowed >= 2);
			assertTrue(where, RootEvolutionTestBed.sameRoot(alice, bob));
		}
	}

	private static int count(Device d, byte kind) {
		int n = 0;
		synchronized (d.receivedKinds) {
			for (byte b : d.receivedKinds) if (b == kind) n++;
		}
		return n;
	}

	private static SecretKey copy(SecretKey k) {
		return new SecretKey(k.getBytes().clone());
	}
}
