package com.professor.zerion.android.grouptr;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Looper;

import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.event.GroupPostReceivedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.sync.MessageId;

import java.nio.charset.StandardCharsets;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupNotificationRawEventTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Test
	public void theRawArrivalOfAPostRaisesNoNotification() {
		ZerionApplication app = ApplicationProvider.getApplicationContext();
		EventListener notifications = (EventListener)
				app.getApplicationComponent().androidNotificationManager();

		notifications.eventOccurred(new GroupPostReceivedEvent(
				new ContactId(1), new MessageId(new byte[32]), new byte[32],
				1L, new byte[32], "Anyone",
				"spam".getBytes(StandardCharsets.UTF_8), 1L, 0L));
		shadowOf(Looper.getMainLooper()).idle();

		Context ctx = ApplicationProvider.getApplicationContext();
		NotificationManager nm = (NotificationManager) ctx.getSystemService(
				Context.NOTIFICATION_SERVICE);
		assertEquals("a post the group manager may refuse raised a"
				+ " notification", 0,
				shadowOf(nm).getAllNotifications().size());
	}
}
