package com.professor.zerion.android.conversation.voice;

import android.Manifest;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;

import com.professor.zerion.android.profile.TestProfileSession;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import org.zerionproject.app.api.messaging.VoiceSignalType;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.R;

import static com.professor.zerion.android.settings.SecurityFragment.PREF_VIDEO_CALLS_ENABLED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VideoOfferPromptTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String CALL_ID = "zt-offer-prompt-call";

	private final CallRig rig = new CallRig(CALL_ID);
	private ActivityController<VoiceCallActivity> screen;

	@Before
	public void setUp() {
		ShadowDialog.reset();
		ShadowToast.reset();
		TestProfileSession.signedInPreferences(rig.app).edit()
				.putBoolean(PREF_VIDEO_CALLS_ENABLED, true).commit();
		shadowOf(rig.app).grantPermissions(Manifest.permission.CAMERA,
				Manifest.permission.RECORD_AUDIO);
	}

	@After
	public void tearDown() {
		TestProfileSession.signedInPreferences(rig.app).edit()
				.remove(PREF_VIDEO_CALLS_ENABLED)
				.commit();
		if (screen != null) {
			try {
				screen.destroy();
			} catch (RuntimeException ignored) {
			}
		}
		rig.tearDown();
	}

	private Dialog promptOnScreen() throws Exception {
		VoiceCallService s = rig.ringingIncoming();
		CallRig.call(s, "cancelCallSetupTimeout");
		CallRig.set(s, "callState", VoiceCallService.CallState.CONNECTED);
		shadowOf(rig.app).setComponentNameAndServiceForBindService(
				new ComponentName(rig.app, VoiceCallService.class),
				s.onBind(null));
		Intent i = new Intent(rig.app, VoiceCallActivity.class);
		i.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, CallRig.CONTACT);
		i.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, true);
		i.putExtra(VoiceCallActivity.EXTRA_CALL_ID, CALL_ID);
		screen = Robolectric.buildActivity(VoiceCallActivity.class, i)
				.setup();
		CallRig.idle();
		rig.peerSends(s, VoiceSignalType.VIDEO_OFFER,
				"REQUEST:00112233445566778899aabbccddeeff");
		CallRig.idle();
		Dialog d = ShadowDialog.getLatestDialog();
		assertNotNull("the request was not put to the user", d);
		assertTrue(d.isShowing());
		return d;
	}

	@Test
	public void anUnansweredPromptClosesWhenTheRequestTimesOut()
			throws Exception {
		Dialog d = promptOnScreen();
		VoiceCallService s = rig.controller.get();
		Runnable timeout = (Runnable) CallRig.get(s,
				"remoteOfferTimeoutRunnable");
		assertNotNull("no timeout was set for the request", timeout);
		timeout.run();
		CallRig.idle();
		assertFalse("the prompt stayed on screen after the timeout",
				d.isShowing());
		assertTrue("the other phone was not told",
				rig.sentTypes().contains("createVideoReject"));
		assertEquals(VoiceCallService.CallState.CONNECTED, s.getCallState());
		assertFalse(screen.get().isFinishing());
	}

	@Test
	public void thePromptClosesWhenTheOtherPhoneTurnsVideoOff()
			throws Exception {
		Dialog d = promptOnScreen();
		VoiceCallService s = rig.controller.get();
		rig.peerSends(s, VoiceSignalType.VIDEO_END, null);
		CallRig.idle();
		assertFalse("the prompt stayed on screen after video was turned off",
				d.isShowing());
		assertEquals(null, CallRig.get(s, "remoteOfferTimeoutRunnable"));
		assertEquals(VoiceCallService.CallState.CONNECTED, s.getCallState());
	}

	@Test
	public void aNewRequestAfterATimeoutIsPutToTheUserAgain()
			throws Exception {
		Dialog first = promptOnScreen();
		VoiceCallService s = rig.controller.get();
		((Runnable) CallRig.get(s, "remoteOfferTimeoutRunnable")).run();
		CallRig.idle();
		assertFalse(first.isShowing());
		rig.peerSends(s, VoiceSignalType.VIDEO_OFFER,
				"REQUEST:ffeeddccbbaa99887766554433221100");
		CallRig.idle();
		Dialog second = ShadowDialog.getLatestDialog();
		assertNotNull(second);
		assertTrue("the second request was not shown", second.isShowing());
		assertTrue(first != second);
	}

	private static Object field(VoiceCallService s, String name) {
		try {
			return CallRig.get(s, name);
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	private int count(String type) {
		int n = 0;
		for (String t : rig.sentTypes()) if (t.equals(type)) n++;
		return n;
	}

	private void earlierSessionEndsWhileTheNewRequestIsOpen(String method)
			throws Exception {
		Dialog d = promptOnScreen();
		VoiceCallService s = rig.controller.get();
		byte[] before = ((byte[]) CallRig.get(s, "remoteVideoNonce")).clone();
		CallRig.call(s, method);
		CallRig.idle();
		assertTrue("the new request was dropped", d.isShowing());
		assertNotNull("the timeout of the new request was cancelled",
				CallRig.get(s, "remoteOfferTimeoutRunnable"));
		byte[] after = (byte[]) CallRig.get(s, "remoteVideoNonce");
		assertNotNull(after);
		assertTrue("the request nonce changed",
				java.util.Arrays.equals(before, after));
		assertEquals(0, count("createVideoEnd"));
		((AlertDialog) d).getButton(AlertDialog.BUTTON_POSITIVE)
				.performClick();
		CallRig.idle();
		CallRig.await(() -> rig.sentTypes().contains("createVideoAccept"),
				15_000);
		assertTrue("the accept was not sent",
				rig.sentTypes().contains("createVideoAccept"));
		assertEquals(0, count("createVideoReject"));
	}

	@Test
	public void refusingAnEarlierVideoKeepsANewRequestOpen() throws Exception {
		earlierSessionEndsWhileTheNewRequestIsOpen("refuseVideoWithoutConsent");
	}

	@Test
	public void tappingVideoWhileAskedAcceptsTheRequest() throws Exception {
		Dialog d = promptOnScreen();
		VoiceCallService s = rig.controller.get();
		s.requestVideoUpgrade();
		CallRig.idle();
		CallRig.await(() -> rig.sentTypes().contains("createVideoAccept"),
				15_000);
		assertEquals(1, count("createVideoAccept"));
		assertEquals(0, count("createVideoOffer"));
		assertFalse("the prompt stayed after the accept", d.isShowing());
	}

	@Test
	public void endingTheCallDropsAnOpenRequest() throws Exception {
		promptOnScreen();
		VoiceCallService s = rig.controller.get();
		CallRig.call(s, "finishCall");
		CallRig.await(() -> field(s, "remoteVideoNonce") == null, 15_000);
		CallRig.idle();
		assertEquals(null, CallRig.get(s, "remoteVideoNonce"));
		assertEquals(null, CallRig.get(s, "remoteOfferTimeoutRunnable"));
		assertFalse(s.isVideoRequested());
		int rejects = count("createVideoReject");
		CallRig.idle();
		assertEquals(rejects, count("createVideoReject"));
	}

	@Test
	public void aFailedEarlierVideoSetupKeepsANewRequestOpen()
			throws Exception {
		earlierSessionEndsWhileTheNewRequestIsOpen("handleVideoSetupFailure");
	}

	@Test
	public void anEarlierVideoStoppingKeepsANewRequestOpen() throws Exception {
		earlierSessionEndsWhileTheNewRequestIsOpen("stopVideoStreaming");
	}

	@Test
	public void aDeclineFromTheAskingPhoneClosesThePromptWithoutANotice()
			throws Exception {
		Dialog d = promptOnScreen();
		VoiceCallService s = rig.controller.get();
		rig.peerSends(s, VoiceSignalType.VIDEO_REJECT, null);
		CallRig.idle();
		assertFalse(d.isShowing());
		assertEquals(0, count("createVideoReject"));
		String declined = rig.app.getString(R.string.call_video_declined);
		assertFalse("the person who was asked saw a declined notice",
				declined.equals(ShadowToast.getTextOfLatestToast()));
		assertEquals(null, CallRig.get(s, "remoteOfferTimeoutRunnable"));
	}

	@Test
	public void anAnswerToAnOldPromptDoesNotAnswerANewRequest()
			throws Exception {
		promptOnScreen();
		VoiceCallService s = rig.controller.get();
		int first = (Integer) CallRig.get(s, "remoteOfferGeneration");
		((Runnable) CallRig.get(s, "remoteOfferTimeoutRunnable")).run();
		CallRig.idle();
		assertEquals(1, count("createVideoReject"));
		s.acceptVideoOffer(first);
		CallRig.idle();
		assertEquals(0, count("createVideoAccept"));
		assertEquals(1, count("createVideoReject"));
		rig.peerSends(s, VoiceSignalType.VIDEO_OFFER,
				"REQUEST:ffeeddccbbaa99887766554433221100");
		CallRig.idle();
		Dialog second = ShadowDialog.getLatestDialog();
		assertTrue(second.isShowing());
		int current = (Integer) CallRig.get(s, "remoteOfferGeneration");
		assertTrue(current != first);
		s.acceptVideoOffer(first);
		s.rejectVideoOffer(first);
		CallRig.idle();
		assertEquals(0, count("createVideoAccept"));
		assertEquals(1, count("createVideoReject"));
		assertTrue("the new request was answered by the old prompt",
				second.isShowing());
		s.acceptVideoOffer(current);
		CallRig.idle();
		CallRig.await(() -> rig.sentTypes().contains("createVideoAccept"),
				15_000);
		assertEquals(1, count("createVideoAccept"));
		assertFalse("the prompt stayed after the accept", second.isShowing());
	}
}
