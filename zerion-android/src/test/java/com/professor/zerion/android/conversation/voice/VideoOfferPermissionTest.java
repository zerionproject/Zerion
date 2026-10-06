package com.professor.zerion.android.conversation.voice;

import android.Manifest;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.widget.Button;

import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.app.api.messaging.VoiceSignalType;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.settings.SecurityFragment.PREF_VIDEO_CALLS_ENABLED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;
import com.professor.zerion.android.profile.TestProfileSession;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VideoOfferPermissionTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String CALL_ID = "zt-offer-permission-call";

	private final CallRig rig = new CallRig(CALL_ID);
	private ActivityController<VoiceCallActivity> screen;

	@Before
	public void setUp() {
		TestProfileSession.signedInPreferences(rig.app).edit()
				.putBoolean(PREF_VIDEO_CALLS_ENABLED, true).commit();
		shadowOf(rig.app).denyPermissions(Manifest.permission.CAMERA);
		shadowOf(rig.app).grantPermissions(Manifest.permission.RECORD_AUDIO);
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

	private static String offer() {
		return "REQUEST:00112233445566778899aabbccddeeff";
	}

	private VoiceCallActivity connectedCallOnScreen() throws Exception {
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
		return screen.get();
	}

	private VoiceCallActivity offerAcceptedWithoutPermission()
			throws Exception {
		VoiceCallActivity a = connectedCallOnScreen();
		VoiceCallService s = rig.controller.get();
		rig.peerSends(s, VoiceSignalType.VIDEO_OFFER, offer());
		CallRig.idle();
		Thread.sleep(300);
		assertFalse("the offer was refused without asking the user",
				rig.sentTypes().contains("createVideoReject"));
		Dialog d = ShadowDialog.getLatestDialog();
		assertNotNull("the offer was not put to the user", d);
		Button accept = ((AlertDialog) d).getButton(
				AlertDialog.BUTTON_POSITIVE);
		accept.performClick();
		CallRig.idle();
		ShadowActivity.PermissionsRequest request =
				shadowOf(a).getLastRequestedPermission();
		assertNotNull("the camera permission was not asked for", request);
		assertEquals(Manifest.permission.CAMERA, request.requestedPermissions[0]);
		return a;
	}

	@Test
	public void theOfferIsAcceptedOnceThePermissionIsGranted()
			throws Exception {
		VoiceCallActivity a = offerAcceptedWithoutPermission();
		int code = shadowOf(a).getLastRequestedPermission().requestCode;
		shadowOf(rig.app).grantPermissions(Manifest.permission.CAMERA);
		a.onRequestPermissionsResult(code,
				new String[] {Manifest.permission.CAMERA},
				new int[] {PackageManager.PERMISSION_GRANTED});
		CallRig.await(() -> rig.sentTypes().contains("createVideoAccept"),
				5_000);
		assertTrue("the offer was not accepted",
				rig.sentTypes().contains("createVideoAccept"));
		assertFalse("a new video request was made instead",
				rig.sentTypes().contains("createVideoOffer"));
		assertTrue(rig.controller.get().isVideoRequested());
	}

	@Test
	public void theOfferIsDeclinedAndTheCallGoesOnWhenThePermissionIsRefused()
			throws Exception {
		VoiceCallActivity a = offerAcceptedWithoutPermission();
		int code = shadowOf(a).getLastRequestedPermission().requestCode;
		a.onRequestPermissionsResult(code,
				new String[] {Manifest.permission.CAMERA},
				new int[] {PackageManager.PERMISSION_DENIED});
		CallRig.await(() -> rig.sentTypes().contains("createVideoReject"),
				5_000);
		assertTrue("the peer was not told",
				rig.sentTypes().contains("createVideoReject"));
		VoiceCallService s = rig.controller.get();
		assertFalse(s.isVideoRequested());
		assertEquals(VoiceCallService.CallState.CONNECTED, s.getCallState());
		assertFalse(a.isFinishing());
	}
}
