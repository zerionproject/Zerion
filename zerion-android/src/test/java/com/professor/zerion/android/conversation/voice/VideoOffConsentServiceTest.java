package com.professor.zerion.android.conversation.voice;

import android.Manifest;
import android.app.Application;
import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.VoiceSignalHeader;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.settings.SecurityFragment.PREF_VIDEO_CALLS_ENABLED;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;
import com.professor.zerion.android.profile.TestProfileSession;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VideoOffConsentServiceTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final int CONTACT = 7;
	private static final String CALL_ID = "zt-video-consent-call";

	private final Application app = ApplicationProvider.getApplicationContext();
	private ServiceController<VoiceCallService> controller;

	@Before
	public void setUp() {
		TestProfileSession.signedInPreferences(app)
				.edit().putBoolean(PREF_VIDEO_CALLS_ENABLED, true).commit();
		shadowOf(app).grantPermissions(Manifest.permission.CAMERA);
	}

	@After
	public void tearDown() {
		TestProfileSession.signedInPreferences(app)
				.edit().remove(PREF_VIDEO_CALLS_ENABLED).commit();
		VoiceCallKeyHolder.clear();
		if (controller != null) {
			try {
				controller.destroy();
			} catch (RuntimeException ignored) {
			}
		}
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static Field field(String name) throws Exception {
		Field f = VoiceCallService.class.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	private static void call(VoiceCallService s, String method)
			throws Exception {
		Method m = VoiceCallService.class.getDeclaredMethod(method);
		m.setAccessible(true);
		m.invoke(s);
	}

	private static void peerSends(VoiceCallService s, VoiceSignalType type,
			String payload) throws Exception {
		VoiceSignalHeader h = new VoiceSignalHeader(new MessageId(new byte[32]),
				new GroupId(new byte[32]), System.currentTimeMillis(), false,
				type, CALL_ID, payload, null);
		Method m = VoiceCallService.class.getDeclaredMethod(
				"handleIncomingVoiceSignal", VoiceSignalHeader.class);
		m.setAccessible(true);
		m.invoke(s, h);
		idle();
	}

	private static String accept() {
		return "ACCEPT:00112233445566778899aabbccddeeff";
	}

	private VoiceCallService connectedVideoCall() throws Exception {
		Intent i = new Intent(app, VoiceCallService.class);
		i.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, CONTACT);
		i.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, true);
		i.putExtra(VoiceCallActivity.EXTRA_CALL_ID, CALL_ID);
		controller = Robolectric.buildService(VoiceCallService.class, i)
				.create().startCommand(0, 1);
		idle();
		VoiceCallService s = controller.get();
		call(s, "cancelCallSetupTimeout");
		field("isIncoming").set(s, false);
		field("isVideoCall").set(s, true);
		field("callState").set(s, VoiceCallService.CallState.CONNECTED);
		return s;
	}

	private VoiceCallService videoRunning() throws Exception {
		VoiceCallService s = connectedVideoCall();
		s.requestVideoUpgrade();
		idle();
		assertTrue("the user's request arms the camera", s.isVideoRequested());
		peerSends(s, VoiceSignalType.VIDEO_ACCEPT, accept());
		field("videoEnabled").set(s, true);
		return s;
	}

	@Test
	public void turningVideoOffWithdrawsTheCameraConsent() throws Exception {
		VoiceCallService s = videoRunning();
		s.endVideo();
		idle();
		assertFalse("the camera stays armed after the user turned video off",
				s.isVideoRequested());
		assertFalse(s.isVideoEnabled());
	}

	@Test
	public void thePeerCannotRestartTheCameraAfterTheUserTurnedItOff()
			throws Exception {
		VoiceCallService s = videoRunning();
		s.endVideo();
		idle();
		peerSends(s, VoiceSignalType.VIDEO_END, null);
		call(s, "autoStartVideoIfNeeded");
		idle();
		assertFalse("a reconnect offered video again without the user",
				s.isVideoRequested());
		peerSends(s, VoiceSignalType.VIDEO_ACCEPT, accept());
		assertFalse(s.isVideoRequested());
		assertNull("the peer's accept was taken",
				field("remoteVideoNonce").get(s));
	}

	@Test
	public void aVideoSessionThePeerEndedIsNotReofferedOnReconnect()
			throws Exception {
		VoiceCallService s = videoRunning();
		peerSends(s, VoiceSignalType.VIDEO_END, null);
		call(s, "autoStartVideoIfNeeded");
		idle();
		assertFalse("a reconnect offered video again without the user",
				s.isVideoRequested());
		peerSends(s, VoiceSignalType.VIDEO_ACCEPT, accept());
		assertFalse(s.isVideoRequested());
	}

	@Test
	public void aLostVideoLinkNeedsTheUserToStartVideoAgain()
			throws Exception {
		VoiceCallService s = videoRunning();
		call(s, "stopVideoStreaming");
		idle();
		assertFalse(s.isVideoEnabled());
		call(s, "autoStartVideoIfNeeded");
		idle();
		assertFalse("video was offered again without the user",
				s.isVideoRequested());
		peerSends(s, VoiceSignalType.VIDEO_ACCEPT, accept());
		assertFalse(s.isVideoRequested());
		assertNull(field("remoteVideoNonce").get(s));
		s.requestVideoUpgrade();
		idle();
		assertTrue("the user can start video again", s.isVideoRequested());
	}

	@Test
	public void anUnansweredVideoRequestExpires() throws Exception {
		VoiceCallService s = connectedVideoCall();
		s.requestVideoUpgrade();
		idle();
		assertTrue(s.isVideoRequested());
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(3));
		assertFalse("the request is still armed long after it was made",
				s.isVideoRequested());
		peerSends(s, VoiceSignalType.VIDEO_ACCEPT, accept());
		assertFalse(s.isVideoRequested());
		assertNull(field("remoteVideoNonce").get(s));
	}
}
