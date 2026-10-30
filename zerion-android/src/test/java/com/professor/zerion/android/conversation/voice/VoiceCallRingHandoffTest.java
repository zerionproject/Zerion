package com.professor.zerion.android.conversation.voice;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;

import java.time.Duration;

import javax.annotation.Nullable;

import androidx.core.app.NotificationCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VoiceCallRingHandoffTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final CallRig rig = new CallRig("zt-ring-handoff");
	@Nullable
	private ServiceController<VoiceCallService> idle;

	@After
	public void tearDown() {
		rig.tearDown();
		if (idle != null) {
			try {
				idle.destroy();
			} catch (RuntimeException ignored) {
			}
		}
	}

	private NotificationManager system() {
		return (NotificationManager)
				rig.app.getSystemService(Context.NOTIFICATION_SERVICE);
	}

	@Nullable
	private Notification tagged(String tag) {
		CallRig.idle();
		for (StatusBarNotification n :
				shadowOf(system()).getActiveNotifications()) {
			if (tag.equals(n.getTag()) && n.getId() == CallRig.CONTACT) {
				return n.getNotification();
			}
		}
		return null;
	}

	private static void advance(long ms) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
	}

	@Test
	public void theCallScreenTakingTheCallStopsTheBackgroundRing()
			throws Exception {
		Notification placeholder = new NotificationCompat.Builder(rig.app,
				"voice_call_channel")
				.setSmallIcon(R.drawable.ic_phone_white).build();
		system().notify(IncomingCallRinger.RING_TAG, CallRig.CONTACT,
				placeholder);
		assertNotNull(tagged(IncomingCallRinger.RING_TAG));

		rig.ringingIncoming();

		assertNull(tagged(IncomingCallRinger.RING_TAG));
	}

	@Test
	public void aRingOnTheCallScreenThatTimesOutLeavesAMissedCall()
			throws Exception {
		rig.ringingIncoming();
		assertNull(tagged(IncomingCallRinger.MISSED_TAG));

		advance(61_000);

		assertNotNull(tagged(IncomingCallRinger.MISSED_TAG));
	}

	@Test
	public void theCallerHangingUpOnTheCallScreenLeavesAMissedCall()
			throws Exception {
		VoiceCallService s = rig.ringingIncoming();

		rig.peerSends(s, VoiceSignalType.CALL_END, null);

		assertNotNull(tagged(IncomingCallRinger.MISSED_TAG));
	}

	@Test
	public void decliningOnTheCallScreenLeavesNoMissedCall()
			throws Exception {
		VoiceCallService s = rig.ringingIncoming();

		s.declineCall();
		CallRig.await(() -> rig.sentTypes().contains("createCallReject"),
				5_000);
		advance(61_000);

		assertNull(tagged(IncomingCallRinger.MISSED_TAG));
	}

	@Test
	public void aDeclineFromTheBackgroundRingStopsTheIdleService() {
		Intent decline = new Intent(rig.app, VoiceCallService.class);
		decline.setAction(CallIntents.ACTION_DECLINE_CALL);
		decline.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, CallRig.CONTACT);
		decline.putExtra(VoiceCallActivity.EXTRA_CALL_ID, "zt-ring-handoff");

		idle = Robolectric.buildService(VoiceCallService.class, decline)
				.create().startCommand(0, 1);
		CallRig.idle();

		assertTrue(shadowOf(idle.get()).isStoppedBySelf());
	}

	private com.professor.zerion.android.api.AndroidNotificationManager
			notifications() {
		return ((com.professor.zerion.android.ZerionApplication) rig.app)
				.getApplicationComponent().androidNotificationManager();
	}

	private void forgetStartedServices() {
		while (shadowOf(rig.app).getNextStartedService() != null) {
			CallRig.idle();
		}
	}

	private Intent screenFor(String callId) {
		Intent i = new Intent(rig.app, VoiceCallActivity.class);
		i.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, CallRig.CONTACT);
		i.putExtra(VoiceCallActivity.EXTRA_IS_INCOMING, true);
		i.putExtra(VoiceCallActivity.EXTRA_CALL_ID, callId);
		return i;
	}

	@Test
	public void aFinishedCallStopsCountingAsBusyBeforeTheServiceIsGone()
			throws Exception {
		VoiceCallService s = rig.ringingIncoming();
		ContactId caller = new ContactId(CallRig.CONTACT);
		CallRig.idle();
		assertTrue(notifications().hasCall(caller, rig.callId));

		s.declineCall();
		CallRig.await(() -> !notifications().hasCall(caller, rig.callId),
				5_000);

		assertFalse("a redial right after the call must ring",
				notifications().hasCall(caller, rig.callId));
	}

	@Test
	public void aDeclineQueuedDuringShutdownIsNotReplayedAsAServiceStart()
			throws Exception {
		VoiceCallService s = rig.ringingIncoming();
		CallRig.set(s, "isShuttingDown", true);
		Intent decline = new Intent(rig.app, VoiceCallService.class);
		decline.setAction(CallIntents.ACTION_DECLINE_CALL);
		decline.putExtra(VoiceCallActivity.EXTRA_CONTACT_ID, 8);
		decline.putExtra(VoiceCallActivity.EXTRA_CALL_ID, "other");
		s.onStartCommand(decline, 0, 2);
		forgetStartedServices();

		rig.controller.destroy();
		rig.controller = null;
		CallRig.idle();

		assertNull(shadowOf(rig.app).getNextStartedService());
	}

	@Test
	public void aCallScreenForAnOfferThatIsGoneClosesAndStartsNothing() {
		forgetStartedServices();
		ActivityController<VoiceCallActivity> screen =
				Robolectric.buildActivity(VoiceCallActivity.class,
						screenFor("gone")).create();
		try {
			assertTrue(screen.get().isFinishing());
			assertNull(shadowOf(rig.app).getNextStartedService());
		} finally {
			screen.destroy();
		}
	}

	@Test
	public void aCallScreenForAHeldOfferStaysOpen() {
		VoiceCallKeyHolder.setOffer(CallRig.CONTACT, "held",
				new SecretKey(new byte[32]), null);
		forgetStartedServices();
		ActivityController<VoiceCallActivity> screen =
				Robolectric.buildActivity(VoiceCallActivity.class,
						screenFor("held")).create();
		try {
			assertFalse(screen.get().isFinishing());
			assertNotNull(shadowOf(rig.app).getNextStartedService());
		} finally {
			screen.destroy();
		}
	}
}
