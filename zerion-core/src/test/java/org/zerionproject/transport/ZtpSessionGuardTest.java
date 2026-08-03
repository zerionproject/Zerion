package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.TorConstants;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for the cross-transport single-session guard in
 * {@link ZtpConnectionHandlerImpl}. The guard methods do not touch the
 * injected components, so a handler built with nulls exercises them directly.
 */
public class ZtpSessionGuardTest {

	private ZtpConnectionHandlerImpl newHandler() {
		return new ZtpConnectionHandlerImpl(null, null, null, null);
	}

	@Test
	public void sameTransportIsAlwaysAllowed() {
		ZtpConnectionHandlerImpl h = newHandler();
		// Two concurrent Tor connections to the same contact both proceed,
		// exactly as before the guard existed (a single-transport config).
		assertTrue(h.acquireSession(1, TorConstants.ID));
		assertTrue(h.acquireSession(1, TorConstants.ID));
		h.releaseSession(1, TorConstants.ID);
		h.releaseSession(1, TorConstants.ID);
	}

	@Test
	public void differentTransportStandsDownWhileOneIsLive() {
		ZtpConnectionHandlerImpl h = newHandler();
		assertTrue(h.acquireSession(1, TorConstants.ID));
		// I2P must not resume the same per-contact ratchet while Tor holds it.
		assertFalse(h.acquireSession(1, I2pConstants.ID));
		h.releaseSession(1, TorConstants.ID);
		// Once Tor releases, I2P may take the session.
		assertTrue(h.acquireSession(1, I2pConstants.ID));
		h.releaseSession(1, I2pConstants.ID);
	}

	@Test
	public void refCountReleasesOnlyWhenLastSameTransportConnectionEnds() {
		ZtpConnectionHandlerImpl h = newHandler();
		assertTrue(h.acquireSession(1, TorConstants.ID));
		assertTrue(h.acquireSession(1, TorConstants.ID));
		h.releaseSession(1, TorConstants.ID);
		// One Tor connection remains, so a different transport is still blocked.
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
