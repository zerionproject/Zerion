package com.professor.zerion.android.conversation.voice;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import javax.annotation.Nullable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VoiceCallRingingNotificationTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final CallRig rig = new CallRig("zt-ringing-notification");

	@After
	public void tearDown() {
		rig.tearDown();
	}

	@Nullable
	private Notification callNotification() {
		CallRig.idle();
		NotificationManager system = (NotificationManager)
				rig.app.getSystemService(Context.NOTIFICATION_SERVICE);
		for (StatusBarNotification n :
				shadowOf(system).getActiveNotifications()) {
			if (n.getTag() == null
					&& n.getId() == VoiceCallService.NOTIFICATION_ID) {
				return n.getNotification();
			}
		}
		return null;
	}

	private String title(Notification n) {
		return n.extras.getCharSequence(Notification.EXTRA_TITLE).toString();
	}

	@Test
	public void aRingingCallScreenKeepsAnswerAndDeclineInItsNotification()
			throws Exception {
		rig.ringingIncoming();

		Notification n = callNotification();
		assertNotNull(n);
		assertEquals(rig.app.getString(
				R.string.call_notification_incoming_title), title(n));
		assertEquals(CallNotification.RING_CHANNEL_ID, n.getChannelId());
		assertNotNull(n.fullScreenIntent);
		assertEquals(2, n.actions.length);
		assertTrue(shadowOf(n.actions[0].actionIntent).getSavedIntent()
				.getBooleanExtra(VoiceCallActivity.EXTRA_ANSWER, false));
		assertEquals(CallIntents.ACTION_DECLINE_CALL,
				shadowOf(n.actions[1].actionIntent).getSavedIntent()
						.getAction());
		assertTrue("the call screen rings, the notification stays quiet",
				(n.flags & Notification.FLAG_INSISTENT) == 0);
	}

	@Test
	@Config(sdk = 32)
	public void fromAndroid12TheRingingCallScreenNotificationIsAnIncomingCall()
			throws Exception {
		rig.ringingIncoming();

		Notification n = callNotification();
		assertNotNull(n);
		assertEquals("android.app.Notification$CallStyle",
				n.extras.getString(Notification.EXTRA_TEMPLATE));
		assertNotNull(n.extras.getParcelable(
				Notification.EXTRA_ANSWER_INTENT));
		assertNotNull(n.extras.getParcelable(
				Notification.EXTRA_DECLINE_INTENT));
	}

	@Test
	public void answeringTurnsTheNotificationIntoAnOngoingCall()
			throws Exception {
		VoiceCallService s = rig.ringingIncoming();

		s.acceptCall();

		Notification n = callNotification();
		assertNotNull(n);
		assertEquals(rig.app.getString(
				R.string.call_notification_ongoing_title), title(n));
		assertNull(n.fullScreenIntent);
		assertTrue(n.actions == null || n.actions.length == 0);
	}
}
