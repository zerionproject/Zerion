package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.TorConstants;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZtpSessionGuardTest {

	private ZtpConnectionHandlerImpl newHandler() {
		return new ZtpConnectionHandlerImpl(null, null, null, null,
				new org.zerionproject.core.test.PermissiveOnionClientAuth());
	}

	@Test
	public void sameTransportIsAlwaysAllowed() {
		ZtpConnectionHandlerImpl h = newHandler();
		assertTrue(h.acquireSession(1, TorConstants.ID));
		assertTrue(h.acquireSession(1, TorConstants.ID));
		h.releaseSession(1, TorConstants.ID);
		h.releaseSession(1, TorConstants.ID);
	}

	@Test
	public void thirdSameTransportConnectionIsRefused() {
		ZtpConnectionHandlerImpl h = newHandler();
		assertTrue(h.acquireSession(1, TorConstants.ID));
		assertTrue(h.acquireSession(1, TorConstants.ID));
		assertFalse(h.acquireSession(1, TorConstants.ID));
		h.releaseSession(1, TorConstants.ID);
		assertTrue(h.acquireSession(1, TorConstants.ID));
		h.releaseSession(1, TorConstants.ID);
		h.releaseSession(1, TorConstants.ID);
	}

	@Test
	public void differentTransportStandsDownWhileOneIsLive() {
		ZtpConnectionHandlerImpl h = newHandler();
		assertTrue(h.acquireSession(1, TorConstants.ID));
		assertFalse(h.acquireSession(1, I2pConstants.ID));
		h.releaseSession(1, TorConstants.ID);
		assertTrue(h.acquireSession(1, I2pConstants.ID));
		h.releaseSession(1, I2pConstants.ID);
	}

	@Test
	public void refCountReleasesOnlyWhenLastSameTransportConnectionEnds() {
		ZtpConnectionHandlerImpl h = newHandler();
		assertTrue(h.acquireSession(1, TorConstants.ID));
		assertTrue(h.acquireSession(1, TorConstants.ID));
		h.releaseSession(1, TorConstants.ID);
		assertFalse(h.acquireSession(1, I2pConstants.ID));
		h.releaseSession(1, TorConstants.ID);
		assertTrue(h.acquireSession(1, I2pConstants.ID));
		h.releaseSession(1, I2pConstants.ID);
	}

	@Test
	public void differentContactsAreIndependent() {
		ZtpConnectionHandlerImpl h = newHandler();
		assertTrue(h.acquireSession(1, TorConstants.ID));
		assertTrue(h.acquireSession(2, I2pConstants.ID));
		h.releaseSession(1, TorConstants.ID);
		h.releaseSession(2, I2pConstants.ID);
	}
}
