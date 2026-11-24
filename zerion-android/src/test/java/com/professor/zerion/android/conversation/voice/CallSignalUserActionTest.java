package com.professor.zerion.android.conversation.voice;

import android.Manifest;

import com.professor.zerion.android.profile.TestProfileSession;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.VoiceSignalFactory;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.zerionproject.core.api.crypto.SecretKey;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.settings.SecurityFragment.PREF_VIDEO_CALLS_ENABLED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class CallSignalUserActionTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String CALL_ID = "zt-user-action-call";
	private static final String BY_USER = " by the user";
	private static final String BY_APP = " by the app";
	private static final String PEER_ONION =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx";

	private final CallRig rig = new CallRig(CALL_ID);
	private final List<String> stored =
			Collections.synchronizedList(new ArrayList<>());
	private final ThreadLocal<String> created = new ThreadLocal<>();

	@Before
	public void setUp() {
		TestProfileSession.signedInPreferences(rig.app).edit()
				.putBoolean(PREF_VIDEO_CALLS_ENABLED, true).commit();
		shadowOf(rig.app).grantPermissions(Manifest.permission.CAMERA);
	}

	@After
	public void tearDown() {
		TestProfileSession.signedInPreferences(rig.app).edit()
				.remove(PREF_VIDEO_CALLS_ENABLED).commit();
		rig.tearDown();
	}

	@Test
	public void placingACallIsAUserAction() throws Exception {
		rig.controller = Robolectric.buildService(VoiceCallService.class,
				rig.intent(false)).create();
		rig.install(rig.controller.get());
		record(rig.controller.get());

		rig.controller.startCommand(0, 1);

		assertStored("createCallOffer" + BY_USER);
	}

	@Test
	public void answeringACallIsAUserAction() throws Exception {
		VoiceCallKeyHolder.setOffer(CallRig.CONTACT, CALL_ID, callKey(),
				null);
		VoiceCallService s = rig.ringingIncoming();
		record(s);

		s.acceptCall();

		assertStored("createCallAnswer" + BY_USER);
	}

	@Test
	public void decliningACallIsAUserAction() throws Exception {
		VoiceCallService s = rig.ringingIncoming();
		record(s);

		s.declineCall();

		assertStored("createCallReject" + BY_USER);
	}

	@Test
	public void hangingUpIsAUserAction() throws Exception {
		VoiceCallService s = rig.ringingOutgoing();
		record(s);

		s.hangUp();

		assertStored("createCallEnd" + BY_USER);
	}

	@Test
	public void theEndSentAfterThePeerHungUpIsNotAUserAction()
			throws Exception {
		VoiceCallService s = rig.ringingOutgoing();
		record(s);

		rig.peerSends(s, VoiceSignalType.CALL_END, null);

		assertStored("createCallEnd" + BY_APP);
	}

	@Test
	public void theEndSentWhenTheAppEndsTheCallIsNotAUserAction()
			throws Exception {
		VoiceCallService s = rig.ringingOutgoing();
		record(s);

		s.endCall();

		assertStored("createCallEnd" + BY_APP);
	}

	@Test
	public void switchingVideoOnAndOffAreUserActions() throws Exception {
		VoiceCallService s = connected();

		s.requestVideoUpgrade();
		assertStored("createVideoOffer" + BY_USER);
		s.endVideo();

		assertStored("createVideoEnd" + BY_USER);
		assertEquals(Arrays.asList("createVideoOffer" + BY_USER,
				"createVideoEnd" + BY_USER), new ArrayList<>(stored));
	}

	@Test
	public void answeringAVideoRequestIsAUserAction() throws Exception {
		VoiceCallService s = connected();
		rig.peerSends(s, VoiceSignalType.VIDEO_OFFER,
				"REQUEST:00112233445566778899aabbccddeeff");
		s.acceptVideoOffer(
				(Integer) CallRig.get(s, "remoteOfferGeneration"));
		assertStored("createVideoAccept" + BY_USER);
	}

	@Test
	public void decliningAVideoRequestIsAUserAction() throws Exception {
		VoiceCallService s = connected();
		rig.peerSends(s, VoiceSignalType.VIDEO_OFFER,
				"REQUEST:00112233445566778899aabbccddeeff");

		s.rejectVideoOffer((Integer) CallRig.get(s, "remoteOfferGeneration"));

		assertStored("createVideoReject" + BY_USER);
	}

	@Test
	public void aVideoRequestRefusedForLackOfTheCameraIsNotAUserAction()
			throws Exception {
		VoiceCallService s = connected();
		rig.peerSends(s, VoiceSignalType.VIDEO_OFFER,
				"REQUEST:00112233445566778899aabbccddeeff");

		s.refuseVideoOfferWithoutCamera(
				(Integer) CallRig.get(s, "remoteOfferGeneration"));

		assertStored("createVideoReject" + BY_APP);
	}

	@Test
	public void aMalformedVideoRequestIsRefusedByTheApp() throws Exception {
		VoiceCallService s = connected();

		rig.peerSends(s, VoiceSignalType.VIDEO_OFFER, "REQUEST:short");

		assertStored("createVideoReject" + BY_APP);
	}

	@Test
	public void aVideoRequestWhileVideoIsSwitchedOffIsRefusedByTheApp()
			throws Exception {
		TestProfileSession.signedInPreferences(rig.app).edit()
				.putBoolean(PREF_VIDEO_CALLS_ENABLED, false).commit();
		VoiceCallService s = connected();

		rig.peerSends(s, VoiceSignalType.VIDEO_OFFER,
				"REQUEST:00112233445566778899aabbccddeeff");

		assertStored("createVideoReject" + BY_APP);
	}

	private VoiceCallService connected() throws Exception {
		VoiceCallService s = rig.ringingOutgoing();
		CallRig.set(s, "voiceCallKey", callKey());
		CallRig.set(s, "callState", VoiceCallService.CallState.CONNECTED);
		CallRig.set(s, "lastRemoteOnion", PEER_ONION);
		record(s);
		return s;
	}

	private void record(VoiceCallService s) throws Exception {
		CallRig.set(s, "voiceSignalFactory", Proxy.newProxyInstance(
				VoiceSignalFactory.class.getClassLoader(),
				new Class<?>[] {VoiceSignalFactory.class}, (p, m, a) -> {
					created.set(m.getName());
					return null;
				}));
		CallRig.set(s, "messagingManager", Proxy.newProxyInstance(
				MessagingManager.class.getClassLoader(),
				new Class<?>[] {MessagingManager.class}, (p, m, a) -> {
					if (m.getName().equals("addLocalVoiceSignal")) {
						stored.add(created.get()
								+ ((Boolean) a[1] ? BY_USER : BY_APP));
					}
					return null;
				}));
	}

	private void assertStored(String signal) throws Exception {
		CallRig.await(() -> stored.contains(signal), 10_000);
		assertTrue("stored: " + stored, stored.contains(signal));
	}

	private static SecretKey callKey() {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) 0x52);
		return new SecretKey(raw);
	}
}
