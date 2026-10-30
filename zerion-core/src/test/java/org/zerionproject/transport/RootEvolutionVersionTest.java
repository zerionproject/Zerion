package org.zerionproject.transport;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.transport.RootEvolutionTestBed.Cut;
import org.zerionproject.transport.RootEvolutionTestBed.Device;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_CONFIRM;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_DONE;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_HELLO;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_INIT;
import static org.zerionproject.transport.RootEvolutionRecord.KIND_RESP;
import static org.zerionproject.transport.RootEvolutionRecord.VERSION;
import static org.zerionproject.transport.RootEvolutionRecord.VERSION_SEPARATED;

public class RootEvolutionVersionTest {

	private static final int ALICE_SEES_BOB = 2;
	private static final int BOB_SEES_ALICE = 1;

	private RootEvolutionTestBed bed;
	private SecretKey pairingRoot;

	@Before
	public void setUp() throws Exception {
		bed = new RootEvolutionTestBed();
		pairingRoot = bed.randomKey();
	}

	private Device alice(boolean separated) {
		Device d = bed.new Device("alice", ALICE_SEES_BOB, true,
				ContactRootKeys.atPairing(copy(pairingRoot)), true);
		manager(d).setSeparatedEvolution(separated);
		return d;
	}

	private Device bob(boolean separated) {
		Device d = bed.new Device("bob", BOB_SEES_ALICE, false,
				ContactRootKeys.atPairing(copy(pairingRoot)), true);
		manager(d).setSeparatedEvolution(separated);
		return d;
	}

	private static RootEvolutionManager manager(Device d) {
		RootEvolutionManager m = d.evolutions;
		assertNotNull(m);
		return m;
	}

	private static List<Byte> evolutionVersions(Device d) {
		List<Byte> versions = new ArrayList<>();
		synchronized (d.receivedRecords) {
			for (byte[] r : d.receivedRecords) {
				if (r[1] == KIND_INIT || r[1] == KIND_RESP
						|| r[1] == KIND_CONFIRM || r[1] == KIND_DONE) {
					versions.add(r[0]);
				}
			}
		}
		return versions;
	}

	private static List<Byte> helloVersions(Device d) {
		List<Byte> versions = new ArrayList<>();
		synchronized (d.receivedRecords) {
			for (byte[] r : d.receivedRecords) {
				if (r[1] == KIND_HELLO) versions.add(r[0]);
			}
		}
		return versions;
	}

	private static void assertOnly(List<Byte> versions, byte version) {
		assertFalse(versions.isEmpty());
		for (Byte v : versions) assertEquals(version, (byte) v);
	}

	private void settle(Device alice, Device bob, String where)
			throws Exception {
		for (int i = 0; i < 3; i++) {
			bed.passTime();
			assertTrue(where + " bob dials " + i, bed.exchange(bob, alice));
			bed.passTime();
			assertTrue(where + " alice dials " + i, bed.exchange(alice, bob));
		}
		assertTrue(where, RootEvolutionTestBed.sameRoot(alice, bob));
		assertTrue(where, alice.keys().getEpoch() >= 1);
		assertNull(where, alice.keys().getPending());
	}

	@Test(timeout = 120_000)
	public void twoCurrentPeersEvolveWithVersionThree() throws Exception {
		Device alice = alice(true);
		Device bob = bob(true);
		assertTrue(bed.exchange(alice, bob));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertEquals(1, alice.keys().getEpoch());
		assertOnly(evolutionVersions(bob), VERSION_SEPARATED);
		assertOnly(evolutionVersions(alice), VERSION_SEPARATED);
		assertOnly(helloVersions(bob), VERSION);
		bed.passTime();
		assertTrue(bed.exchange(bob, alice));
		assertTrue(bed.exchange(alice, bob));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertTrue(alice.keys().getEpoch() >= 2);
	}

	@Test(timeout = 120_000)
	public void aVersionThreePendingRootHoldsAConfirmKeyOfItsOwn()
			throws Exception {
		Device alice = alice(true);
		Device bob = bob(true);
		Cut cut = new Cut("alice", KIND_CONFIRM);
		alice.cut = cut;
		bed.connect(alice, bob, () -> cut.fired);
		assertTrue(cut.fired);
		alice.cut = null;
		ContactRootKeys a = alice.keys();
		ContactRootKeys b = bob.keys();
		assertNotNull(a.getPending());
		assertNotNull(b.getPending());
		assertTrue(a.isPendingSeparated());
		assertTrue(b.isPendingSeparated());
		assertTrue(Arrays.equals(a.getPending().getBytes(),
				b.getPending().getBytes()));
		assertTrue(Arrays.equals(a.getPendingConfirmKey().getBytes(),
				b.getPendingConfirmKey().getBytes()));
		assertFalse(Arrays.equals(a.getPending().getBytes(),
				a.getPendingConfirmKey().getBytes()));
		RootEvolutionCrypto crypto = new RootEvolutionCrypto(bed.crypto);
		assertFalse(Arrays.equals(
				crypto.legacyConfirmKey(a.getPending()).getBytes(),
				a.getPendingConfirmKey().getBytes()));
		settle(alice, bob, "after the cut");
	}

	@Test(timeout = 120_000)
	public void aCurrentAliceUsesVersionTwoWithAnEarlierBob()
			throws Exception {
		Device alice = alice(true);
		Device bob = bob(false);
		assertTrue(bed.exchange(alice, bob));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertEquals(1, alice.keys().getEpoch());
		assertOnly(evolutionVersions(bob), VERSION);
		assertOnly(evolutionVersions(alice), VERSION);
		bed.passTime();
		assertTrue(bed.exchange(bob, alice));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
	}

	@Test(timeout = 120_000)
	public void aCurrentBobAnswersVersionTwoFromAnEarlierAlice()
			throws Exception {
		Device alice = alice(false);
		Device bob = bob(true);
		assertTrue(bed.exchange(alice, bob));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
		assertEquals(1, alice.keys().getEpoch());
		assertOnly(evolutionVersions(bob), VERSION);
		assertOnly(evolutionVersions(alice), VERSION);
		bed.passTime();
		assertTrue(bed.exchange(bob, alice));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
	}

	@Test(timeout = 120_000)
	public void anEarlierPeerIgnoresVersionThreeRecords() throws Exception {
		Device alice = alice(true);
		Device bob = bob(false);
		byte[] init = RootEvolutionRecord.init(VERSION_SEPARATED, 0,
				new byte[32], new byte[32], new byte[1184]);
		manager(bob).newEvolution(new org.zerionproject.core.api.contact
				.ContactId(BOB_SEES_ALICE), false).onRecord(init);
		assertNull(bob.keys().getPending());
		assertTrue(bed.exchange(alice, bob));
		assertTrue(RootEvolutionTestBed.sameRoot(alice, bob));
	}

	@Test(timeout = 600_000)
	public void anEvolutionCutOffAtAnyStepRecoversInEveryPairing()
			throws Exception {
		Object[][] cuts = {
				{"alice", KIND_HELLO},
				{"bob", KIND_HELLO},
				{"alice", KIND_INIT},
				{"bob", KIND_RESP},
				{"alice", KIND_CONFIRM},
				{"bob", KIND_DONE},
		};
		boolean[][] pairings = {
				{true, true}, {true, false}, {false, true}, {false, false}
		};
		for (boolean[] p : pairings) {
			for (Object[] c : cuts) {
				setUp();
				Device alice = alice(p[0]);
				Device bob = bob(p[1]);
				String where = "alice " + p[0] + " bob " + p[1] + " cut "
						+ c[0] + " kind " + c[1];
				Device cutter = c[0].equals("alice") ? alice : bob;
				Cut cut = new Cut((String) c[0], (Byte) c[1]);
				cutter.cut = cut;
				bed.connect(alice, bob, () -> cut.fired);
				assertTrue(where, cut.fired);
				cutter.cut = null;
				settle(alice, bob, where);
				byte expected = p[0] && p[1] ? VERSION_SEPARATED : VERSION;
				assertOnly(evolutionVersions(alice), expected);
				assertOnly(evolutionVersions(bob), expected);
			}
		}
	}

	@Test(timeout = 300_000)
	public void anEvolutionStartedBeforeAnUpdateCompletesAfterIt()
			throws Exception {
		Object[][] cuts = {
				{"alice", KIND_INIT},
				{"bob", KIND_RESP},
				{"alice", KIND_CONFIRM},
				{"bob", KIND_DONE},
		};
		for (int updated = 0; updated < 3; updated++) {
			for (Object[] c : cuts) {
				setUp();
				Device alice = alice(false);
				Device bob = bob(false);
				String where = "updated " + (updated == 0 ? "alice"
						: updated == 1 ? "bob" : "both") + " cut " + c[0]
						+ " kind " + c[1];
				Device cutter = c[0].equals("alice") ? alice : bob;
				Cut cut = new Cut((String) c[0], (Byte) c[1]);
				cutter.cut = cut;
				bed.connect(alice, bob, () -> cut.fired);
				assertTrue(where, cut.fired);
				cutter.cut = null;
				if (updated != 1) manager(alice).setSeparatedEvolution(true);
				if (updated != 0) manager(bob).setSeparatedEvolution(true);
				alice.receivedRecords.clear();
				bob.receivedRecords.clear();
				settle(alice, bob, where);
				boolean nothingProved = (Byte) c[1] == KIND_INIT
						|| (Byte) c[1] == KIND_RESP;
				if (updated == 2 && nothingProved) {
					assertOnly(evolutionVersions(bob), VERSION_SEPARATED);
				}
				manager(alice).setSeparatedEvolution(true);
				manager(bob).setSeparatedEvolution(true);
				alice.receivedRecords.clear();
				bob.receivedRecords.clear();
				long before = alice.keys().getEpoch();
				bed.passTime();
				assertTrue(where, bed.exchange(alice, bob));
				assertTrue(where, RootEvolutionTestBed.sameRoot(alice, bob));
				assertTrue(where, alice.keys().getEpoch() > before);
				assertOnly(evolutionVersions(bob), VERSION_SEPARATED);
			}
		}
	}

	private static SecretKey copy(SecretKey k) {
		return new SecretKey(k.getBytes().clone());
	}
}
