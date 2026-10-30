package com.professor.zerion.android.contact.add.nearby.ble;

import org.junit.Test;

import static com.professor.zerion.android.contact.add.nearby.ble.PairingCentralGate.Verdict.ACCEPTED;
import static com.professor.zerion.android.contact.add.nearby.ble.PairingCentralGate.Verdict.BACK_OFF;
import static com.professor.zerion.android.contact.add.nearby.ble.PairingCentralGate.Verdict.REFUSED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PairingCentralGateTest {

	private final byte[] commitment = {1, 2, 3, 4};
	private final byte[] right = PairingCentralGate.proof(commitment);
	private final byte[] wrong = PairingCentralGate.proof(new byte[] {9});
	private final PairingCentralGate gate = new PairingCentralGate(commitment);

	@Test
	public void theWaitForAProofIsLongEnoughForASlowPhone() {
		assertEquals(8_000L, PairingCentralGate.PROOF_TIMEOUT_MS);
	}

	@Test
	public void theFirstCentralWithTheRightProofIsChosen() {
		assertTrue(gate.onConnected("A"));
		assertTrue(gate.onConnected("B"));
		assertEquals(ACCEPTED, gate.onProof("B", right, true));
		assertTrue(gate.isChosen("B"));
		assertEquals(REFUSED, gate.onProof("A", right, true));
		assertEquals(ACCEPTED, gate.onProof("B", right, true));
		assertFalse(gate.onConnected("C"));
	}

	@Test
	public void aRightProofIsAcceptedWithoutAnyConnectEvent() {
		assertEquals(ACCEPTED, gate.onProof("A", right, true));
	}

	@Test
	public void aRightProofIsAcceptedAfterTheWaitRanOut() {
		gate.onConnected("A");
		gate.onTimeout("A");
		assertEquals(ACCEPTED, gate.onProof("A", right, true));
	}

	@Test
	public void timeoutsAndDisconnectsAreNeverCounted() {
		for (int i = 0; i < 50; i++) {
			gate.onConnected("X" + i);
			if (i % 2 == 0) gate.onTimeout("X" + i);
			else gate.onDisconnected("X" + i);
		}
		assertEquals(ACCEPTED, gate.onProof("A", right, true));
	}

	@Test
	public void onlyThreeCentralsWaitAtOnce() {
		assertTrue(gate.onConnected("A"));
		assertTrue(gate.onConnected("B"));
		assertTrue(gate.onConnected("C"));
		assertFalse(gate.onConnected("D"));
		assertEquals(3, gate.pendingCount());
		gate.onTimeout("A");
		assertTrue(gate.onConnected("D"));
	}

	@Test
	public void fiveWrongProofsStartABackOffWithAFreshCount() {
		for (int i = 0; i < 4; i++) {
			assertEquals(REFUSED, gate.onProof("X" + i, wrong, true));
		}
		assertEquals(BACK_OFF, gate.onProof("X4", wrong, true));
		assertEquals(REFUSED, gate.onProof("A", right, true));
		gate.endBackOff();
		for (int i = 0; i < 4; i++) {
			assertEquals(REFUSED, gate.onProof("Y" + i, wrong, true));
		}
		assertEquals(ACCEPTED, gate.onProof("A", right, true));
	}

	@Test
	public void uncountedWrongProofsNeverStartABackOff() {
		for (int i = 0; i < 20; i++) {
			assertEquals(REFUSED, gate.onProof("OWN", wrong, false));
		}
		assertEquals(ACCEPTED, gate.onProof("A", right, true));
	}
}
