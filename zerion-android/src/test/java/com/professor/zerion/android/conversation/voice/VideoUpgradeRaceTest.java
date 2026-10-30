package com.professor.zerion.android.conversation.voice;

import android.Manifest;
import android.os.Looper;

import com.professor.zerion.android.profile.TestProfileSession;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.settings.SecurityFragment.PREF_VIDEO_CALLS_ENABLED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VideoUpgradeRaceTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String CALL_ID = "zt-video-upgrade-race-call";
	private static final String PEER_ONION =
			"abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx";
	private static final String ACCEPT =
			"ACCEPT:00112233445566778899aabbccddeeff";
	private static final String OFFER =
			"REQUEST:00112233445566778899aabbccddeeff";
	private static final String OTHER_OFFER =
			"REQUEST:ffeeddccbbaa99887766554433221100";

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
				.remove(PREF_VIDEO_CALLS_ENABLED).commit();
		rig.tearDown();
	}

	private static SecretKey callKey() {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) 0x37);
		return new SecretKey(raw);
	}

	private VoiceCallService connectedCallee() throws Exception {
		VoiceCallService s = rig.ringingIncoming();
		CallRig.call(s, "cancelCallSetupTimeout");
		CallRig.set(s, "voiceCallKey", callKey());
		CallRig.set(s, "callState", VoiceCallService.CallState.CONNECTED);
		assertTrue("video is not allowed on this phone: settings "
						+ CallRig.call(s, "videoEnabledInSettings")
						+ ", camera " + CallRig.call(s, "cameraPermitted"),
				(Boolean) CallRig.call(s, "videoAllowedLocally"));
		return s;
	}

	private VoiceCallService connectedCaller() throws Exception {
		VoiceCallService s = rig.ringingOutgoing();
		CallRig.set(s, "voiceCallKey", callKey());
		CallRig.set(s, "callState", VoiceCallService.CallState.CONNECTED);
		CallRig.set(s, "lastRemoteOnion", PEER_ONION);
		return s;
	}

	private static void peerConnects(VoiceCallService s,
			DuplexTransportConnection conn) throws Exception {
		Method m = VoiceCallService.class.getDeclaredMethod(
				"onEndpointConnection", DuplexTransportConnection.class);
		m.setAccessible(true);
		m.invoke(s, conn);
		CallRig.idle();
	}

	private void awaitSent(String type) throws InterruptedException {
		CallRig.await(() -> rig.sentTypes().contains(type), 10_000);
	}

	private int rejects() {
		return Collections.frequency(rig.sentTypes(), "createVideoReject");
	}

	private static void idleFor(Duration d) {
		shadowOf(Looper.getMainLooper()).idleFor(d);
	}

	private VoiceCallService calleeAskedForVideoAndThePeerConnected(
			DuplexTransportConnection conn) throws Exception {
		VoiceCallService s = connectedCallee();
		s.requestVideoUpgrade();
		CallRig.idle();
		assertTrue(s.isVideoRequested());
		awaitSent("createVideoOffer");
		peerConnects(s, conn);
		Thread.sleep(200);
		CallRig.idle();
		return s;
	}

	@Test
	public void aVideoConnectionArrivingBeforeTheAcceptWaitsForIt()
			throws Exception {
		DuplexTransportConnection conn = rig.connection();
		VoiceCallService s = calleeAskedForVideoAndThePeerConnected(conn);
		assertEquals("the early video connection was thrown away",
				0, rig.disposed.get());
		assertTrue("the video request was given up",
				s.isVideoRequested());
		assertSame(conn, CallRig.get(s, "parkedVideoConnection"));
		assertEquals(0, rejects());

		rig.peerSends(s, VoiceSignalType.VIDEO_ACCEPT, ACCEPT);
		CallRig.await(() -> {
			try {
				return CallRig.get(s, "parkedVideoConnection") == null;
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}, 10_000);
		assertNull("the accept did not pick up the waiting connection",
				CallRig.get(s, "parkedVideoConnection"));
		assertTrue("the callee dialled instead of using the connection",
				rig.dialled.isEmpty());
		assertEquals(0, rejects());
		assertEquals(VoiceCallService.CallState.CONNECTED, s.getCallState());
	}

	@Test
	public void aWaitingVideoConnectionIsDroppedWhenTheAcceptNeverComes()
			throws Exception {
		DuplexTransportConnection conn = rig.connection();
		VoiceCallService s = calleeAskedForVideoAndThePeerConnected(conn);
		assertEquals(0, rig.disposed.get());

		idleFor(Duration.ofSeconds(31));
		CallRig.await(() -> rig.disposed.get() >= 2, 10_000);
		assertTrue("the connection was kept beyond the bounded wait",
				rig.disposed.get() >= 2);
		assertEquals("the socket was closed on the main thread", 0,
				rig.disposedOnMain.get());
		assertNull(CallRig.get(s, "parkedVideoConnection"));
		assertFalse("the request stayed armed without a connection",
				s.isVideoRequested());

		rig.peerSends(s, VoiceSignalType.VIDEO_ACCEPT, ACCEPT);
		awaitSent("createVideoReject");
		assertEquals("a late accept was not answered with one reject",
				1, rejects());
		assertEquals(VoiceCallService.CallState.CONNECTED, s.getCallState());
		assertFalse(s.isVideoEnabled());
	}

	@Test
	public void aWaitingVideoConnectionIsClosedWhenTheCallEnds()
			throws Exception {
		DuplexTransportConnection conn = rig.connection();
		VoiceCallService s = calleeAskedForVideoAndThePeerConnected(conn);
		assertEquals(0, rig.disposed.get());

		s.endCall();
		CallRig.await(() -> rig.disposed.get() >= 2, 10_000);
		assertTrue("the waiting connection outlived the call",
				rig.disposed.get() >= 2);
		assertNull(CallRig.get(s, "parkedVideoConnection"));
	}

	@Test
	public void aWaitingVideoConnectionIsClosedWhenThePeerRejects()
			throws Exception {
		DuplexTransportConnection conn = rig.connection();
		VoiceCallService s = calleeAskedForVideoAndThePeerConnected(conn);
		assertEquals(0, rig.disposed.get());

		rig.peerSends(s, VoiceSignalType.VIDEO_REJECT, null);
		CallRig.await(() -> rig.disposed.get() >= 2, 10_000);
		assertTrue("the waiting connection survived the reject",
				rig.disposed.get() >= 2);
		assertNull(CallRig.get(s, "parkedVideoConnection"));
		assertFalse(s.isVideoRequested());
		assertEquals(VoiceCallService.CallState.CONNECTED, s.getCallState());
	}

	@Test
	public void anAcceptAfterTheRequestWindowGetsOneRejectAndTheCallGoesOn()
			throws Exception {
		VoiceCallService s = connectedCaller();
		s.requestVideoUpgrade();
		CallRig.idle();
		assertTrue(s.isVideoRequested());

		idleFor(Duration.ofMinutes(3));
		assertFalse(s.isVideoRequested());

		rig.peerSends(s, VoiceSignalType.VIDEO_ACCEPT, ACCEPT);
		awaitSent("createVideoReject");
		assertEquals("a stale accept was not answered with one reject",
				1, rejects());
		assertTrue("a stale accept started a video dial",
				rig.dialled.isEmpty());
		assertFalse(s.isVideoRequested());
		assertFalse(s.isVideoEnabled());
		assertEquals(VoiceCallService.CallState.CONNECTED, s.getCallState());
		assertNull(CallRig.get(s, "remoteVideoNonce"));
	}

	@Test
	public void anAcceptNobodyAskedForGetsOneReject() throws Exception {
		VoiceCallService s = connectedCaller();
		rig.peerSends(s, VoiceSignalType.VIDEO_ACCEPT, ACCEPT);
		awaitSent("createVideoReject");
		assertEquals(1, rejects());
		assertTrue(rig.dialled.isEmpty());
		assertFalse(s.isVideoRequested());
		assertEquals(VoiceCallService.CallState.CONNECTED, s.getCallState());
	}

	@Test
	public void aSecondAcceptWhileVideoRunsIsIgnored() throws Exception {
		VoiceCallService s = connectedCaller();
		s.requestVideoUpgrade();
		CallRig.idle();
		rig.dialResult = rig.connection();
		rig.peerSends(s, VoiceSignalType.VIDEO_ACCEPT, ACCEPT);
		CallRig.await(() -> !rig.dialled.isEmpty(), 10_000);
		assertEquals(1, rig.dialled.size());
		CallRig.set(s, "videoEnabled", true);

		rig.peerSends(s, VoiceSignalType.VIDEO_ACCEPT, ACCEPT);
		Thread.sleep(1500);
		CallRig.idle();
		assertEquals("a duplicate accept tore down running video",
				0, rejects());
		assertEquals("a duplicate accept dialled again",
				1, rig.dialled.size());
		assertTrue(s.isVideoEnabled());
	}

	@Test
	public void anAcceptWhileTheCallReconnectsIsRejectedAndTheRequestDropped()
			throws Exception {
		VoiceCallService s = connectedCaller();
		s.requestVideoUpgrade();
		CallRig.idle();
		assertTrue(s.isVideoRequested());
		CallRig.set(s, "callState", VoiceCallService.CallState.CONNECTING);

		rig.peerSends(s, VoiceSignalType.VIDEO_ACCEPT, ACCEPT);
		awaitSent("createVideoReject");
		assertEquals("the peer was left waiting for a video link",
				1, rejects());
		assertTrue("video was dialled during a reconnect",
				rig.dialled.isEmpty());
		assertFalse("the request stayed armed through the reconnect",
				s.isVideoRequested());
		assertEquals(VoiceCallService.CallState.CONNECTING, s.getCallState());
	}

	@Test
	public void aRepeatedOfferKeepsTheFirstOne() throws Exception {
		VoiceCallService s = connectedCallee();
		rig.peerSends(s, VoiceSignalType.VIDEO_OFFER, OFFER);
		byte[] first = (byte[]) CallRig.get(s, "remoteVideoNonce");
		assertTrue(first != null);

		rig.peerSends(s, VoiceSignalType.VIDEO_OFFER, OTHER_OFFER);
		assertSame("a repeated offer replaced the pending one",
				first, CallRig.get(s, "remoteVideoNonce"));
		Thread.sleep(1500);
		assertEquals(0, rejects());
	}

	@Test
	public void aCallerStartedUpgradeStillDialsOnAccept() throws Exception {
		VoiceCallService s = connectedCaller();
		s.requestVideoUpgrade();
		CallRig.idle();
		rig.dialResult = rig.connection();
		rig.peerSends(s, VoiceSignalType.VIDEO_ACCEPT, ACCEPT);
		CallRig.await(() -> !rig.dialled.isEmpty(), 10_000);
		assertEquals(Collections.singletonList(PEER_ONION), rig.dialled);
		assertEquals(0, rejects());
	}

	private static Method starter() throws Exception {
		Method m = VoiceCallService.class.getDeclaredMethod(
				"startVideoOnAcceptedConnection",
				DuplexTransportConnection.class);
		m.setAccessible(true);
		return m;
	}

	private VoiceCallService calleeAcceptedTheOffer() throws Exception {
		VoiceCallService s = connectedCallee();
		rig.peerSends(s, VoiceSignalType.VIDEO_OFFER, OFFER);
		s.acceptVideoOffer();
		CallRig.idle();
		awaitSent("createVideoAccept");
		return s;
	}

	@Test
	public void twoStartersAfterTheAcceptKeepOneConnection()
			throws Exception {
		VoiceCallService s = calleeAcceptedTheOffer();
		DuplexTransportConnection a = rig.connection();
		DuplexTransportConnection b = rig.connection();
		Method start = starter();
		CountDownLatch go = new CountDownLatch(1);
		AtomicReference<Throwable> failed = new AtomicReference<>();
		Runnable[] starters = {
				() -> {
					try {
						go.await();
						start.invoke(s, a);
					} catch (Throwable t) {
						failed.set(t);
					}
				},
				() -> {
					try {
						go.await();
						start.invoke(s, b);
					} catch (Throwable t) {
						failed.set(t);
					}
				}
		};
		Thread first = new Thread(starters[0]);
		Thread second = new Thread(starters[1]);
		first.start();
		second.start();
		go.countDown();
		first.join(10_000);
		second.join(10_000);
		CallRig.idle();
		assertNull(failed.get());
		Object kept = CallRig.get(s, "videoTorConnection");
		assertTrue("no starter kept its connection", kept == a || kept == b);
		assertEquals("exactly the other connection was closed", 2,
				rig.disposed.get());
		assertTrue("the keys of the winner were destroyed",
				CallRig.get(s, "videoKeys") != null);
		assertEquals(0, rejects());
		assertEquals(VoiceCallService.CallState.CONNECTED, s.getCallState());
	}

	@Test
	public void aStarterChecksTheCallUnderTheLockAndDropsTheConnection()
			throws Exception {
		VoiceCallService s = calleeAcceptedTheOffer();
		DuplexTransportConnection conn = rig.connection();
		Method start = starter();
		Object lock = CallRig.get(s, "videoStartLock");
		AtomicReference<Throwable> failed = new AtomicReference<>();
		Thread starter = new Thread(() -> {
			try {
				start.invoke(s, conn);
			} catch (Throwable t) {
				failed.set(t);
			}
		});
		synchronized (lock) {
			starter.start();
			Thread.sleep(300);
			assertNull("the starter did not wait for the lock",
					CallRig.get(s, "videoTorConnection"));
			assertEquals(0, rig.disposed.get());
			CallRig.set(s, "callState", VoiceCallService.CallState.DISCONNECTED);
		}
		starter.join(10_000);
		assertNull(failed.get());
		assertEquals("the connection was not dropped", 2, rig.disposed.get());
		assertNull(CallRig.get(s, "videoTorConnection"));
		assertNull(CallRig.get(s, "videoKeys"));
	}

	@Test
	public void aConnectionAfterTheAcceptStartsVideoAtOnce() throws Exception {
		VoiceCallService s = connectedCallee();
		rig.peerSends(s, VoiceSignalType.VIDEO_OFFER, OFFER);
		s.acceptVideoOffer();
		CallRig.idle();
		awaitSent("createVideoAccept");
		assertTrue(rig.sentTypes().contains("createVideoAccept"));
		assertTrue(rig.dialled.isEmpty());

		DuplexTransportConnection conn = rig.connection();
		peerConnects(s, conn);
		assertNull("the connection was parked although the accept was sent",
				CallRig.get(s, "parkedVideoConnection"));
		assertEquals(0, rejects());
	}
}
