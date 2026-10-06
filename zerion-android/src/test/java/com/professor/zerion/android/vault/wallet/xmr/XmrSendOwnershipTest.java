package com.professor.zerion.android.vault.wallet.xmr;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class XmrSendOwnershipTest {

	private final Object prepared = new Object();
	private final Object session = new Object();
	private final Object flow = new Object();

	private XmrSendOwnership own() {
		return new XmrSendOwnership(prepared, session, "w1", 5, 5, flow);
	}

	@Test
	public void matchesOnlyTheExactBindings() {
		assertTrue(own().matches(prepared, session, "w1", 5, 5, flow));
	}

	@Test
	public void replacedNativeObjectFails() {
		assertFalse(own().matches(new Object(), session, "w1", 5, 5, flow));
	}

	@Test
	public void replacedSessionFails() {
		assertFalse(own().matches(prepared, new Object(), "w1", 5, 5, flow));
	}

	@Test
	public void changedEpochFails() {
		assertFalse(own().matches(prepared, session, "w1", 6, 5, flow));
	}

	@Test
	public void changedLockGenerationFails() {
		assertFalse(own().matches(prepared, session, "w1", 5, 6, flow));
	}

	@Test
	public void walletSwitchFails() {
		assertFalse(own().matches(prepared, session, "w2", 5, 5, flow));
	}

	@Test
	public void wrongFlowFails() {
		assertFalse(own().matches(prepared, session, "w1", 5, 5, new Object()));
	}
}
