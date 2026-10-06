package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.transport.RootEvolutionTestBed.Cut;
import org.zerionproject.transport.RootEvolutionTestBed.Device;
import org.zerionproject.transport.RootEvolutionTestBed.Outcome;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_CONFIRM;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_DONE;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_HELLO;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_INIT;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_RESP;

public class ContactRootEvolutionTest {

	private static final int ALICE_SEES_BOB = 2;
	private static final int BOB_SEES_ALICE = 1;

	private RootEvolutionTestBed bed;
	private SecretKey pairingRoot;

	@Before
	public void setUp() throws Exception {
		bed = new RootEvolutionTestBed();
		pairingRoot = bed.randomKey();
	}

	private Device alice(boolean evolves) {
		return bed.new Device("alice", ALICE_SEES_BOB, true,
				ContactRootKeys.atPairing(copy(pairingRoot)), evolves);
	}

	private Device bob(boolean evolves) {
		return bed.new Device("bob", BOB_SEES_ALICE, false,
				ContactRootKeys.atPairing(copy(pairingRoot)), evolves);
	}

	@Test(timeout = 120_000)
	public void connectionsEvolveTheRootOnBothSides() throws Exception {
		Device alice = alice(true);
		Device bob = bob(true);
		assertTrue(bed.exchange(alice, bob));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertEquals(1, alice.keys().getEpoch());
		assertFalse(Arrays.equals(pairingRoot.getBytes(),
				alice.keys().getCurrent().getBytes()));
		bed.passTime();
		assertTrue(bed.exchange(bob, alice));
		assertTrue(bed.exchange(alice, bob));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertTrue(alice.keys().getEpoch() >= 2);
	}

	@Test(timeout = 120_000)
	public void copiedPairingRootNoLongerAuthenticatesAfterAnEvolution()
			throws Exception {
		Device alice = alice(true);
		Device bob = bob(true);
		assertTrue(bed.exchange(alice, bob));
		assertTrue(bed.exchange(bob, alice));
		assertEquals(1, bob.keys().getEpoch());

		Device malloryAsAlice = bed.new Device("mallory", ALICE_SEES_BOB,
				true, ContactRootKeys.atPairing(copy(pairingRoot)), false);
		int bobBefore = bob.received.size();
		Outcome o = bed.connect(malloryAsAlice, bob,
				() -> bob.received.size() > bobBefore);
		assertTrue(RootEvolutionTestBed.isFormatException(o.acceptorError));
		assertEquals(bobBefore, bob.received.size());

		Device malloryAsBob = bed.new Device("mallory", BOB_SEES_ALICE,
				false, ContactRootKeys.atPairing(copy(pairingRoot)), false);
		int aliceBefore = alice.received.size();
		o = bed.connect(malloryAsBob, alice,
				() -> alice.received.size() > aliceBefore);
		assertTrue(RootEvolutionTestBed.isFormatException(o.acceptorError));
		assertEquals(aliceBefore, alice.received.size());

		assertTrue(bed.exchange(alice, bob));
	}

	@Test(timeout = 120_000)
	public void copiedEvolvedRootStopsAuthenticatingAfterTheNextEvolution()
			throws Exception {
		Device alice = alice(true);
		Device bob = bob(true);
		assertTrue(bed.exchange(alice, bob));
		ContactRootKeys stolen = copy(bob.keys());
		bed.passTime();
		assertTrue(bed.exchange(bob, alice));
		bed.passTime();
		assertTrue(bed.exchange(alice, bob));
		assertTrue(bob.keys().getEpoch() > stolen.getEpoch());

		Device malloryAsAlice = bed.new Device("mallory", ALICE_SEES_BOB,
				true, new ContactRootKeys(stolen.getEpoch(),
				stolen.getCurrent(), null, false), false);
		int bobBefore = bob.received.size();
		Outcome o = bed.connect(malloryAsAlice, bob,
				() -> bob.received.size() > bobBefore);
		assertTrue(RootEvolutionTestBed.isFormatException(o.acceptorError));
		assertEquals(bobBefore, bob.received.size());
	}

	@Test(timeout = 300_000)
	public void anEvolutionCutOffAtAnyStepNeverStopsThePairConnecting()
			throws Exception {
		Object[][] cuts = {
				{"alice", KIND_HELLO},
				{"bob", KIND_HELLO},
				{"alice", KIND_INIT},
				{"bob", KIND_RESP},
				{"alice", KIND_CONFIRM},
				{"bob", KIND_DONE},
		};
		for (Object[] c : cuts) {
			setUp();
			Device alice = alice(true);
			Device bob = bob(true);
			String where = c[0] + " kind " + c[1];
			Device cutter = c[0].equals("alice") ? alice : bob;
			Cut cut = new Cut((String) c[0], (Byte) c[1]);
			cutter.cut = cut;
			bed.connect(alice, bob, () -> cut.fired);
			assertTrue(where, cut.fired);
			cutter.cut = null;
			for (int i = 0; i < 4; i++) {
				bed.passTime();
				assertTrue(where + " bob dials " + i,
						bed.exchange(bob, alice));
				bed.passTime();
				assertTrue(where + " alice dials " + i,
						bed.exchange(alice, bob));
			}
			assertTrue(where, RootEvolutionTestBed.sameRoot(alice, bob));
			assertTrue(where, alice.keys().getEpoch() >= 1);
			assertNull(where, alice.keys().getPending());
		}
	}

	@Test(timeout = 300_000)
	public void anEvolutionCutOffWhileOnlyTheSideThatDidNotDialHeldTheNewRoot()
			throws Exception {
		Object[][] cuts = {
				{"bob", KIND_RESP},
				{"alice", KIND_CONFIRM},
				{"bob", KIND_DONE},
		};
		for (Object[] c : cuts) {
			setUp();
			Device alice = alice(true);
			Device bob = bob(true);
			String where = c[0] + " kind " + c[1];
			Device cutter = c[0].equals("alice") ? alice : bob;
			Cut cut = new Cut((String) c[0], (Byte) c[1]);
			cutter.cut = cut;
			bed.connect(bob, alice, () -> cut.fired);
			assertTrue(where, cut.fired);
			cutter.cut = null;
			bed.passTime();
			assertTrue(where, bed.exchange(bob, alice));
			bed.passTime();
			assertTrue(where, bed.exchange(bob, alice));
			bed.passTime();
			assertTrue(where, bed.exchange(alice, bob));
			assertTrue(where, RootEvolutionTestBed.sameRoot(alice, bob));
		}
	}

	@Test(timeout = 120_000)
	public void twoConnectionsAtOnceEvolveTheRootOnce() throws Exception {
		Device alice = alice(true);
		Device bob = bob(true);
		bed.glare(alice, bob);
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertEquals(1, alice.keys().getEpoch());
		bed.passTime();
		assertTrue(bed.exchange(bob, alice));
		assertTrue(bed.exchange(alice, bob));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
	}

	@Test(timeout = 120_000)
	public void aPeerWithoutRootEvolutionKeepsThePairingRoot()
			throws Exception {
		Device alice = alice(true);
		Device legacyBob = bob(false);
		for (int i = 0; i < 2; i++) {
			int a = alice.received.size();
			int b = legacyBob.received.size();
			bed.connect(alice, legacyBob, () -> alice.received.size() > a
					&& legacyBob.received.size() > b);
			assertTrue(alice.received.size() > a);
			assertTrue(legacyBob.received.size() > b);
			bed.passTime();
			int a2 = alice.received.size();
			int b2 = legacyBob.received.size();
			bed.connect(legacyBob, alice, () -> alice.received.size() > a2
					&& legacyBob.received.size() > b2);
			assertTrue(alice.received.size() > a2);
			assertTrue(legacyBob.received.size() > b2);
		}
		assertEquals(0, alice.keys().getEpoch());
		assertNull(alice.keys().getPending());
		assertArrayEquals(pairingRoot.getBytes(),
				alice.keys().getCurrent().getBytes());
	}

	@Test(timeout = 120_000)
	public void pausedEvolutionKeepsTheRootWhileTheAccountIsMoved()
			throws Exception {
		Device alice = alice(true);
		Device bob = bob(true);
		alice.store.paused = true;
		bob.store.paused = true;
		int a = alice.received.size();
		int b = bob.received.size();
		bed.connect(alice, bob, () -> alice.received.size() > a
				&& bob.received.size() > b);
		assertTrue(alice.received.size() > a && bob.received.size() > b);
		assertEquals(0, alice.keys().getEpoch());
		assertEquals(0, bob.keys().getEpoch());
		assertNull(bob.keys().getPending());
		alice.store.paused = false;
		bob.store.paused = false;
		bed.passTime();
		assertTrue(bed.exchange(alice, bob));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
	}

	private static SecretKey copy(SecretKey k) {
		return new SecretKey(k.getBytes().clone());
	}

	private static ContactRootKeys copy(ContactRootKeys k) {
		SecretKey p = k.getPending();
		return new ContactRootKeys(k.getEpoch(), copy(k.getCurrent()),
				p == null ? null : copy(p), k.isPendingConfirmed());
	}
}
