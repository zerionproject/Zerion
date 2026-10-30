package com.professor.zerion.android.conversation.voice;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;

import javax.annotation.Nullable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VoiceCallLateAnswerTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String CALL_ID = "zt-late-answer";
	private static final String FAILED_TAG = "zerion.call.failed";
	private static final String MISSED_TAG = "zerion.call.missed";
	private static final long RING_MS = 60_000;
	private static final long ANSWER_WAIT_MS = RING_MS + 15_000;
	private static final long CONNECT_WAIT_MS = 45_000;

	private final CallRig rig = new CallRig(CALL_ID);

	@After
	public void tearDown() {
		rig.tearDown();
	}

	private static void advance(long ms) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
	}

	private static String state(VoiceCallService s) throws Exception {
		return String.valueOf(CallRig.get(s, "callState"));
	}

	private static SecretKey callKey() {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) 0x44);
		return new SecretKey(raw);
	}

	private static byte[] contribution(int b) {
		byte[] raw = new byte[32];
		Arrays.fill(raw, (byte) b);
		return raw;
	}

	@Nullable
	private Notification tagged(String tag) {
		CallRig.idle();
		NotificationManager system = (NotificationManager)
				rig.app.getSystemService(Context.NOTIFICATION_SERVICE);
		for (StatusBarNotification n :
				shadowOf(system).getActiveNotifications()) {
			if (tag.equals(n.getTag()) && n.getId() == CallRig.CONTACT) {
				return n.getNotification();
			}
		}
		return null;
	}

	private static String title(Notification n) {
		return n.extras.getCharSequence(Notification.EXTRA_TITLE).toString();
	}

	private VoiceCallService ringingCaller() throws Exception {
		VoiceCallService s = rig.startedWithRig(false);
		CallRig.await(() -> {
			try {
				return "RINGING".equals(state(s));
			} catch (Exception e) {
				return false;
			}
		}, 10_000);
		assertEquals("RINGING", state(s));
		CallRig.await(() -> rig.sentTypes().contains("createCallOffer"),
				5_000);
		return s;
	}

	private VoiceCallService ringingCallee() throws Exception {
		VoiceCallKeyHolder.setOffer(CallRig.CONTACT, CALL_ID, callKey(),
				contribution(0x0B));
		VoiceCallService s = rig.startedWithRig(true);
		assertEquals("RINGING", state(s));
		return s;
	}

	@Test
	public void theCallerStillRingsAfterThirtyFiveSeconds() throws Exception {
		VoiceCallService s = ringingCaller();

		advance(40_000);

		assertEquals("an answer late in the ring must still reach the caller",
				"RINGING", state(s));
		assertNull(tagged(FAILED_TAG));
	}

	@Test
	public void theCallerGivesUpWithNoAnswerOnceTheRingIsOver()
			throws Exception {
		VoiceCallService s = ringingCaller();

		advance(ANSWER_WAIT_MS - 1_000);
		assertEquals("RINGING", state(s));

		advance(2_000);

		assertEquals("FAILED", state(s));
		Notification notice = tagged(FAILED_TAG);
		assertNotNull("the caller learns nobody answered", notice);
		assertEquals("No answer", title(notice));
		CallRig.await(() -> rig.sentTypes().contains("createCallEnd"), 5_000);
		assertTrue("the callee is told the call is over",
				rig.sentTypes().contains("createCallEnd"));
	}

	@Test
	public void aStoppedCallScreenStillGetsTheNoAnswerNotice()
			throws Exception {
		VoiceCallService s = ringingCaller();
		CallRig.set(s, "callActivity",
				org.mockito.Mockito.mock(VoiceCallActivity.class));

		advance(ANSWER_WAIT_MS + 1_000);

		assertEquals("FAILED", state(s));
		Notification notice = tagged(FAILED_TAG);
		assertNotNull("a bound but stopped call screen shows nothing",
				notice);
		assertEquals("No answer", title(notice));
	}

	@Test
	public void aRepeatedAnswerNeitherRestartsTheWindowNorDialsAgain()
			throws Exception {
		VoiceCallService s = rig.ringingOutgoing();
		KeyPair ours = rig.crypto().generateCallAgreementKeyPair();
		CallRig.set(s, "voiceCallKey", callKey());
		CallRig.set(s, "localEphemeralSecret", contribution(0x0A));
		CallRig.set(s, "localAgreementKeyPair", ours);
		String onion = rig.realManager().expectedPeerOnion(CALL_ID, callKey(),
				true);
		String answer = CallSignalPayloads.formatAnswer(onion, 80,
				contribution(0x0B), rig.crypto().encodeCallAgreementPublicKey(
						rig.crypto().generateCallAgreementKeyPair()));
		rig.peerSends(s, VoiceSignalType.CALL_ANSWER, answer);
		assertEquals("CONNECTING", state(s));
		CallRig.await(() -> rig.dialled.size() == 1, 5_000);

		advance(30_000);
		rig.peerSends(s, VoiceSignalType.CALL_ANSWER, answer);
		CallRig.idle();
		assertEquals("a repeated answer leaves the call connecting",
				"CONNECTING", state(s));
		assertEquals("a repeated answer starts no second dial", 1,
				rig.dialled.size());

		advance(CONNECT_WAIT_MS - 30_000 + 1_000);
		assertEquals("the first answer's window still ends the attempt",
				"FAILED", state(s));
	}

	@Test
	public void aLateAnswerGetsAFullConnectWindow() throws Exception {
		VoiceCallService s = rig.ringingOutgoing();
		KeyPair ours = rig.crypto().generateCallAgreementKeyPair();
		CallRig.set(s, "voiceCallKey", callKey());
		CallRig.set(s, "localEphemeralSecret", contribution(0x0A));
		CallRig.set(s, "localAgreementKeyPair", ours);
		Method ring = VoiceCallService.class.getDeclaredMethod(
				"scheduleCallSetupTimeout", long.class);
		ring.setAccessible(true);
		ring.invoke(s, ANSWER_WAIT_MS);
		advance(55_000);

		String onion = rig.realManager().expectedPeerOnion(CALL_ID, callKey(),
				true);
		rig.peerSends(s, VoiceSignalType.CALL_ANSWER,
				CallSignalPayloads.formatAnswer(onion, 80, contribution(0x0B),
						rig.crypto().encodeCallAgreementPublicKey(
								rig.crypto().generateCallAgreementKeyPair())));
		assertEquals("CONNECTING", state(s));

		advance(40_000);
		assertEquals("the answer restarts the clock for connecting",
				"CONNECTING", state(s));

		advance(CONNECT_WAIT_MS - 40_000 + 1_000);
		assertEquals("FAILED", state(s));
		Notification notice = tagged(FAILED_TAG);
		assertNotNull(notice);
		assertEquals("Could not connect", title(notice));
	}

	@Test
	public void theCalleePreparesItsHiddenServiceWhileItRings()
			throws Exception {
		ringingCallee();

		CallRig.await(() -> rig.createdEndpoints.contains(CALL_ID), 5_000);

		assertEquals("the onion is being published before Answer is tapped",
				1, rig.createdEndpoints.size());
	}

	@Test
	public void answeringUsesThePreparedHiddenService() throws Exception {
		VoiceCallService s = ringingCallee();
		CallRig.await(() -> rig.createdEndpoints.contains(CALL_ID), 5_000);

		s.acceptCall();
		CallRig.await(() -> rig.sentTypes().contains("createCallAnswer"),
				5_000);

		assertTrue(rig.sentTypes().contains("createCallAnswer"));
		assertEquals("no second hidden service", 1,
				rig.createdEndpoints.size());
	}

	@Test
	public void aConnectionBeforeAnswerDoesNotPickUpTheCall()
			throws Exception {
		VoiceCallService s = ringingCallee();
		CallRig.await(() -> rig.endpointHandler != null, 5_000);
		assertNotNull(rig.endpointHandler);

		rig.endpointHandler.handleConnection(rig.connection());
		CallRig.idle();

		assertEquals("RINGING", state(s));
		assertTrue(rig.disposed.get() > 0);
	}

	@Test
	public void aCalleeThatAnsweredButNeverConnectsIsToldSoWithoutAMissedCall()
			throws Exception {
		VoiceCallService s = ringingCallee();
		s.acceptCall();
		CallRig.await(() -> rig.sentTypes().contains("createCallAnswer"),
				5_000);

		advance(CONNECT_WAIT_MS + 1_000);

		assertEquals("FAILED", state(s));
		Notification notice = tagged(FAILED_TAG);
		assertNotNull(notice);
		assertEquals("Could not connect", title(notice));
		assertNull(tagged(MISSED_TAG));
	}
}
