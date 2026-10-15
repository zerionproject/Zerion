package org.zerionproject.transport;

import org.zerionproject.core.api.connection.InterruptibleConnection;
import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.TorConstants;
import org.junit.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZtpSessionGuardTest {

	private static final class Conn implements InterruptibleConnection {
		@Override
		public void interruptOutgoingSession() {
		}

		@Override
		public void forceClose() {
		}
	}

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

	@Test
	public void theOtherSidesSessionIsRefusedWhileTheDialersIsLive() {
		ZtpConnectionHandlerImpl h = newHandler();
		assertTrue(h.acquireSession(1, TorConstants.ID, true));
		h.sessionLive(1, TorConstants.ID, true, new Conn(),
				new AtomicBoolean());
		assertFalse(h.acquireSession(1, TorConstants.ID, false));
		assertTrue("a second session of the dialer, such as a probe, is kept",
				h.acquireSession(1, TorConstants.ID, true));
	}

	@Test
	public void theDialersSessionClosesTheOtherSidesSession() {
		ZtpConnectionHandlerImpl h = newHandler();
		Conn other = new Conn();
		assertTrue(h.acquireSession(1, TorConstants.ID, false));
		assertEquals(Collections.emptyList(), h.sessionLive(1,
				TorConstants.ID, false, other, new AtomicBoolean()));
		assertTrue(h.acquireSession(1, TorConstants.ID, true));
		assertEquals(Collections.singletonList(other), h.sessionLive(1,
				TorConstants.ID, true, new Conn(), new AtomicBoolean()));
	}

	@Test
	public void theOtherSidesSessionClosesItselfWhenTheDialersCameFirst() {
		ZtpConnectionHandlerImpl h = newHandler();
		Conn other = new Conn();
		assertTrue(h.acquireSession(1, TorConstants.ID, false));
		assertTrue(h.acquireSession(1, TorConstants.ID, true));
		AtomicBoolean dialerCounted = new AtomicBoolean();
		assertEquals(Collections.emptyList(), h.sessionLive(1,
				TorConstants.ID, true, new Conn(), dialerCounted));
		AtomicBoolean otherCounted = new AtomicBoolean();
		assertEquals(Collections.singletonList(other), h.sessionLive(1,
				TorConstants.ID, false, other, otherCounted));
		assertTrue(dialerCounted.get());
		assertFalse(otherCounted.get());
	}

	@Test
	public void theOtherSideMayConnectAgainOnceTheDialersSessionEnds() {
		ZtpConnectionHandlerImpl h = newHandler();
		Conn dialer = new Conn();
		AtomicBoolean counted = new AtomicBoolean();
		assertTrue(h.acquireSession(1, TorConstants.ID, true));
		h.sessionLive(1, TorConstants.ID, true, dialer, counted);
		h.releaseSession(1, TorConstants.ID, true, counted.get(), dialer);
		assertTrue(h.acquireSession(1, TorConstants.ID, false));
	}
}
