package com.professor.zerion.android.decoy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Core Decoy Mode gate invariant. The gate is process-scoped: after the OS kills
 * the backgrounded process (GrapheneOS), the passed flag is false again, so a
 * reopen must show the calculator, never the real UI/login. Entering the code in
 * the current process suppresses the gate until the next process death.
 */
public class DecoyGateDecisionTest {

	@Test
	public void configuredAndNotYetPassedRequiresCalculator() {
		assertTrue("fresh process with decoy configured must show calculator",
				DecoyGate.decide(false, true));
	}

	@Test
	public void passedThisProcessSuppressesGate() {
		assertFalse("after entering the code, the real app may proceed",
				DecoyGate.decide(true, true));
	}

	@Test
	public void notConfiguredNeverGates() {
		assertFalse(DecoyGate.decide(false, false));
		assertFalse(DecoyGate.decide(true, false));
	}

	@Test
	public void processDeathReArmsTheGate() {
		boolean passedBeforeKill = true;
		boolean passedAfterKill = false;
		assertFalse("before the kill the gate was suppressed",
				DecoyGate.decide(passedBeforeKill, true));
		assertTrue("after process death the gate is required again",
				DecoyGate.decide(passedAfterKill, true));
	}
}
