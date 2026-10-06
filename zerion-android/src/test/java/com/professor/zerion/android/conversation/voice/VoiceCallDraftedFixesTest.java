package com.professor.zerion.android.conversation.voice;

import android.Manifest;

import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.zerionproject.core.api.crypto.SecretKey;

import java.util.Arrays;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.settings.SecurityFragment.PREF_VIDEO_CALLS_ENABLED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;
import com.professor.zerion.android.profile.TestProfileSession;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VoiceCallDraftedFixesTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String CALL_ID = "zt-drafted-call";

	private final CallRig rig = new CallRig(CALL_ID);

	@Before
	public void setUp() {
		TestProfileSession.signedInPreferences(rig.app).edit()
				.putBoolean(PREF_VIDEO_CALLS_ENABLED, true).commit();
		shadowOf(rig.app).grantPermissions(Manifest.permission.CAMERA);
	}

	@After
	public void tearDown() {
		TestProfileSession.signedInPreferences(rig.app).edit()
				.remove(PREF_VIDEO_CALLS_ENABLED)
				.commit();
		rig.tearDown();
	}

	private static SecretKey callKey() {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) 0x52);
		return new SecretKey(raw);
	}

	@Test
	public void aRefusedVideoStartLeavesVideoOffAndTellsThePeer()
			throws Exception {
		VoiceCallService s = rig.ringingOutgoing();
		CallRig.set(s, "voiceCallKey", callKey());
		CallRig.set(s, "callState", VoiceCallService.CallState.CONNECTED);
		CallRig.set(s, "lastRemoteOnion",
				"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx");
		s.requestVideoUpgrade();
		CallRig.idle();
		assertTrue(s.isVideoRequested());
		rig.dialResult = rig.connection();
		rig.onDial = () -> TestProfileSession.signedInPreferences(rig.app).edit()
				.putBoolean(PREF_VIDEO_CALLS_ENABLED, false).commit();
		rig.peerSends(s, VoiceSignalType.VIDEO_ACCEPT,
				"ACCEPT:00112233445566778899aabbccddeeff");
		CallRig.await(() -> rig.sentTypes().contains("createVideoEnd"),
				10_000);
		assertTrue("the peer was not told the video ended",
				rig.sentTypes().contains("createVideoEnd"));
		assertFalse("video is marked on", s.isVideoEnabled());
		assertFalse(s.isVideoRequested());
		assertNull("capture was set up",
				CallRig.get(s, "videoStreamManager"));
		CallRig.await(() -> rig.disposed.get() >= 2, 5_000);
		assertTrue("the video connection was left open",
				rig.disposed.get() >= 2);
	}

	@Test
	public void anEndpointPublishedAfterAFailedSetupIsClosedUnanswered()
			throws Exception {
		VoiceCallKeyHolder.setOffer(CallRig.CONTACT, CALL_ID, callKey(),
				null);
		VoiceCallService s = rig.ringingIncoming();
		rig.onCreateEndpoint = () -> {
			try {
				CallRig.set(s, "endpointsClosed", true);
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		};
		s.acceptCall();
		CallRig.await(() -> rig.closedEndpoints.contains(CALL_ID), 10_000);
		Thread.sleep(500);
		CallRig.idle();
		assertEquals(java.util.Collections.singletonList(CALL_ID),
				rig.createdEndpoints);
		assertTrue("the endpoint stayed published",
				rig.closedEndpoints.contains(CALL_ID));
		assertFalse("an answer was sent for a closed endpoint",
				rig.sentTypes().contains("createCallAnswer"));
	}
}
