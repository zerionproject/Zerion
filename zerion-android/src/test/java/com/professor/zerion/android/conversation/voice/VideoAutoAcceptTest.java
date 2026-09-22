package com.professor.zerion.android.conversation.voice;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The peer's next video offer is accepted without asking only when the local
 * user answered an incoming video call, and only once; a call started as
 * audio, an outgoing video call, or anything after the video session ended
 * asks the user.
 */
public class VideoAutoAcceptTest {

	@Test
	public void onlyAnAnsweredIncomingVideoCallArmsAndOnlyOnce() {
		VideoAutoAccept a = new VideoAutoAccept();
		assertFalse(a.consumeForOffer());
		a.callStarted(true);
		assertTrue(a.isArmed());
		assertTrue(a.consumeForOffer());
		assertFalse("consumed by the first offer", a.consumeForOffer());
	}

	@Test
	public void anAudioCallNeverArms() {
		VideoAutoAccept a = new VideoAutoAccept();
		a.callStarted(false);
		assertFalse(a.isArmed());
		assertFalse(a.consumeForOffer());
	}

	@Test
	public void theEndOfAVideoSessionDisarms() {
		VideoAutoAccept a = new VideoAutoAccept();
		a.callStarted(true);
		a.videoEnded();
		assertFalse(a.consumeForOffer());
	}

	@Test
	public void thereIsNoWayToArmAfterTheCallStarted() throws Exception {
		VideoAutoAccept a = new VideoAutoAccept();
		a.callStarted(false);
		for (java.lang.reflect.Method m : VideoAutoAccept.class
				.getDeclaredMethods()) {
			assertFalse("only callStarted may arm: " + m.getName(),
					m.getName().startsWith("arm") || m.getName().startsWith("set"));
		}
		assertFalse(a.consumeForOffer());
	}
}
