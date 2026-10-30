package com.professor.zerion.android.conversation.voice;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.service.notification.StatusBarNotification;

import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.contact.ContactId;

import javax.annotation.Nullable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VoiceCallNotificationIdTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final int FIRST_CONTACT_MESSAGE_ID = 1001;

	private final CallRig rig = new CallRig("zt-notification-id");

	@After
	public void tearDown() {
		rig.tearDown();
	}

	@Nullable
	private Notification byId(int id) {
		CallRig.idle();
		NotificationManager system = (NotificationManager)
				rig.app.getSystemService(Context.NOTIFICATION_SERVICE);
		for (StatusBarNotification n :
				shadowOf(system).getActiveNotifications()) {
			if (n.getTag() == null && n.getId() == id) {
				return n.getNotification();
			}
		}
		return null;
	}

	@Test
	public void aMessageFromContactOneDoesNotReplaceTheCallNotification()
			throws Exception {
		rig.ringingIncoming();
		Notification before = byId(VoiceCallService.NOTIFICATION_ID);
		assertNotNull(before);
		assertEquals(Notification.CATEGORY_CALL, before.category);

		((ZerionApplication) rig.app).getApplicationComponent()
				.androidNotificationManager()
				.showPrivateMessageNotification(new ContactId(1));

		assertNotNull("the message from contact 1 is shown",
				byId(FIRST_CONTACT_MESSAGE_ID));
		Notification call = byId(VoiceCallService.NOTIFICATION_ID);
		assertNotNull(call);
		assertEquals("the call notification survives the message",
				Notification.CATEGORY_CALL, call.category);
	}
}
