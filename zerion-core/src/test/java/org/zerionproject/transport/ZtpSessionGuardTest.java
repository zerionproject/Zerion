package org.zerionproject.transport;

import org.zerionproject.core.api.connection.InterruptibleConnection;
import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.transport.ZtpConnectionHandlerImpl.SessionConnection;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

	private static SessionConnection recording(List<String> steps) {
		ZwfControlHandler control = new ZwfControlHandler() {
			@Override
			public void start(Sender sender) {
			}

			@Override
			public void onRecord(byte[] payload) {
			}

			@Override
			public void onPeerStreamAuthenticated(long epoch) {
			}

			@Override
			public void closingAsRedundant() {
				steps.add("redundant");
			}

			@Override
			public void close() {
			}
		};
		return new SessionConnection(new ByteArrayInputStream(new byte[0]),
				new ByteArrayOutputStream() {
					@Override
					public void close() throws IOException {
						steps.add("closed");
						super.close();
					}
				}, control);
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

	@Test
	public void aSessionClosedAsRedundantIsToldSoBeforeItsStreamsClose() {
		ZtpConnectionHandlerImpl h = newHandler();
		List<String> steps = new ArrayList<>();
		SessionConnection other = recording(steps);
		assertTrue(h.acquireSession(1, TorConstants.ID, false));
		h.sessionLiveClosingRedundant(1, TorConstants.ID, false, other,
				new AtomicBoolean());
		assertEquals(Collections.emptyList(), steps);
		assertTrue(h.acquireSession(1, TorConstants.ID, true));
		h.sessionLiveClosingRedundant(1, TorConstants.ID, true, new Conn(),
				new AtomicBoolean());
		assertEquals(Arrays.asList("redundant", "closed"), steps);
	}

	@Test
	public void theOtherSidesSessionThatClosesItselfIsToldItIsRedundant() {
		ZtpConnectionHandlerImpl h = newHandler();
		List<String> steps = new ArrayList<>();
		SessionConnection other = recording(steps);
		assertTrue(h.acquireSession(1, TorConstants.ID, false));
		assertTrue(h.acquireSession(1, TorConstants.ID, true));
		h.sessionLiveClosingRedundant(1, TorConstants.ID, true, new Conn(),
				new AtomicBoolean());
		assertEquals(Collections.emptyList(), steps);
		h.sessionLiveClosingRedundant(1, TorConstants.ID, false, other,
				new AtomicBoolean());
		assertEquals(Arrays.asList("redundant", "closed"), steps);
	}

	@Test
	public void aSessionInterruptedAsTheLowerOfTwoIsToldItIsRedundantBeforeItsStreamsClose() {
		List<String> steps = new ArrayList<>();
		SessionConnection interrupted = recording(steps);
		interrupted.interruptOutgoingSession();
		assertEquals(Arrays.asList("redundant", "closed"), steps);
		interrupted.forceClose();
		assertEquals(Arrays.asList("redundant", "closed"), steps);
	}

	@Test
	public void aSessionClosedForAnotherReasonIsNotToldItIsRedundant() {
		List<String> steps = new ArrayList<>();
		SessionConnection closed = recording(steps);
		closed.forceClose();
		closed.forceClose();
		assertEquals(Collections.singletonList("closed"), steps);
	}
}
