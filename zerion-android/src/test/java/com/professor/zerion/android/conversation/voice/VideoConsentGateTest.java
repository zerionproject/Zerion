package com.professor.zerion.android.conversation.voice;

import com.professor.zerion.android.conversation.voice.VideoConsentGate.OfferDecision;

import org.junit.Test;

import java.util.Random;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Adversarial call-state tests for the camera consent invariant: no
 * sequence of remote signals may arm local capture, and every local arming
 * requires a connected call with video allowed.
 */
public class VideoConsentGateTest {

	@Test
	public void testRemoteOfferAloneNeverArmsCapture() {
		VideoConsentGate g = new VideoConsentGate();
		assertEquals(OfferDecision.PROMPT_USER, g.onRemoteOffer(true, true));
		assertTrue(g.isRemoteOfferPending());
		assertFalse(g.isCaptureArmed());
		assertFalse(g.mayStartCapture(true, true));
	}

	@Test
	public void testRemoteOfferWhileVideoDisabledIsRejectedAndNotPending() {
		VideoConsentGate g = new VideoConsentGate();
		assertEquals(OfferDecision.REJECT_NOT_ALLOWED,
				g.onRemoteOffer(true, false));
		assertFalse(g.isRemoteOfferPending());
		assertFalse(g.onLocalAccept(true, true));
		assertFalse(g.mayStartCapture(true, true));
	}

	@Test
	public void testRemoteOfferBeforeConnectedIsIgnored() {
		VideoConsentGate g = new VideoConsentGate();
		assertEquals(OfferDecision.IGNORE, g.onRemoteOffer(false, true));
		assertFalse(g.isRemoteOfferPending());
	}

	@Test
	public void testUnsolicitedRemoteAcceptIsIgnored() {
		VideoConsentGate g = new VideoConsentGate();
		assertFalse(g.onRemoteAccept());
		assertFalse(g.isCaptureArmed());
		assertFalse(g.mayStartCapture(true, true));
	}

	@Test
	public void testRemoteOfferThenRemoteAcceptDoesNotArm() {
		VideoConsentGate g = new VideoConsentGate();
		g.onRemoteOffer(true, true);
		assertFalse(g.onRemoteAccept());
		assertFalse(g.mayStartCapture(true, true));
	}

	@Test
	public void testLocalAcceptArmsOnlyWithPendingOfferConnectedAndAllowed() {
		VideoConsentGate g = new VideoConsentGate();
		assertFalse(g.onLocalAccept(true, true));
		g.onRemoteOffer(true, true);
		assertFalse(g.onLocalAccept(false, true));
		assertFalse(g.isCaptureArmed());
		g.onRemoteOffer(true, true);
		assertFalse(g.onLocalAccept(true, false));
		assertFalse(g.isCaptureArmed());
		g.onRemoteOffer(true, true);
		assertTrue(g.onLocalAccept(true, true));
		assertTrue(g.mayStartCapture(true, true));
	}

	@Test
	public void testCaptureIsNotStartedIfVideoBecomesDisallowedLater() {
		VideoConsentGate g = new VideoConsentGate();
		g.onRemoteOffer(true, true);
		assertTrue(g.onLocalAccept(true, true));
		assertFalse(g.mayStartCapture(true, false));
		assertFalse(g.mayStartCapture(false, true));
	}

	@Test
	public void testLocalRejectOrTimeoutClearsPendingOffer() {
		VideoConsentGate g = new VideoConsentGate();
		g.onRemoteOffer(true, true);
		g.onLocalRejectOrTimeout();
		assertFalse(g.isRemoteOfferPending());
		assertFalse(g.onLocalAccept(true, true));
		assertFalse(g.isCaptureArmed());
	}

	@Test
	public void testLocalRequestThenRemoteAcceptProceeds() {
		VideoConsentGate g = new VideoConsentGate();
		assertTrue(g.onLocalRequest(true, true));
		assertTrue(g.onRemoteAccept());
		assertTrue(g.mayStartCapture(true, true));
		assertFalse(g.onRemoteAccept());
	}

	@Test
	public void testLocalRequestRequiresConnectedAndAllowed() {
		VideoConsentGate g = new VideoConsentGate();
		assertFalse(g.onLocalRequest(false, true));
		assertFalse(g.onLocalRequest(true, false));
		assertFalse(g.isCaptureArmed());
	}

	@Test
	public void testRemoteRejectDisarmsLocalRequest() {
		VideoConsentGate g = new VideoConsentGate();
		g.onLocalRequest(true, true);
		g.onRemoteReject();
		assertFalse(g.isCaptureArmed());
		assertFalse(g.mayStartCapture(true, true));
	}

	@Test
	public void testAudioToVideoToAudioToVideoNeedsFreshConsentEachTime() {
		VideoConsentGate g = new VideoConsentGate();
		g.onRemoteOffer(true, true);
		assertTrue(g.onLocalAccept(true, true));
		assertTrue(g.mayStartCapture(true, true));
		g.reset();
		assertFalse(g.mayStartCapture(true, true));
		assertEquals(OfferDecision.PROMPT_USER, g.onRemoteOffer(true, true));
		assertFalse(g.mayStartCapture(true, true));
		assertTrue(g.onLocalAccept(true, true));
		assertTrue(g.mayStartCapture(true, true));
	}

	@Test
	public void testReconnectWhileArmedStillNeedsAllowedAndConnected() {
		VideoConsentGate g = new VideoConsentGate();
		g.onLocalRequest(true, true);
		assertTrue(g.mayStartCapture(true, true));
		assertFalse(g.mayStartCapture(false, true));
	}

	@Test
	public void testOfferWhileAlreadyArmedIsIgnored() {
		VideoConsentGate g = new VideoConsentGate();
		g.onLocalRequest(true, true);
		assertEquals(OfferDecision.IGNORE, g.onRemoteOffer(true, true));
		assertFalse(g.isRemoteOfferPending());
	}

	@Test
	public void testRandomRemoteSignalSequencesNeverArm() {
		Random r = new Random(7);
		for (int run = 0; run < 2000; run++) {
			VideoConsentGate g = new VideoConsentGate();
			int steps = 1 + r.nextInt(12);
			for (int i = 0; i < steps; i++) {
				boolean connected = r.nextBoolean();
				boolean allowed = r.nextBoolean();
				switch (r.nextInt(4)) {
					case 0: g.onRemoteOffer(connected, allowed); break;
					case 1: g.onRemoteAccept(); break;
					case 2: g.onRemoteReject(); break;
					default: g.reset(); break;
				}
				assertFalse("run " + run, g.isCaptureArmed());
				assertFalse("run " + run, g.mayStartCapture(true, true));
			}
		}
	}

	@Test
	public void testSimultaneousOfferAndLocalRequestArmsOnlyOnce() {
		VideoConsentGate g = new VideoConsentGate();
		assertTrue(g.onLocalRequest(true, true));
		assertEquals(OfferDecision.IGNORE, g.onRemoteOffer(true, true));
		assertFalse(g.onLocalAccept(true, true));
		assertTrue(g.isCaptureArmed());
	}
}
