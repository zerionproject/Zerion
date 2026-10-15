package com.professor.zerion.android.grouptr;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Looper;

import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.event.GroupTrPostAcceptedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrPostsRemovedEvent;
import org.zerionproject.core.api.event.EventListener;

import java.util.Collections;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupNotificationRemovedEventTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Test
	public void aDeletedUnreadPostIsTakenOffTheNotification() {
		ZerionApplication app = ApplicationProvider.getApplicationContext();
		EventListener notifications = (EventListener)
				app.getApplicationComponent().androidNotificationManager();
		Context ctx = ApplicationProvider.getApplicationContext();
		NotificationManager nm = (NotificationManager) ctx.getSystemService(
				Context.NOTIFICATION_SERVICE);
		byte[] group = new byte[32];
		List<byte[]> one = Collections.singletonList(new byte[32]);

		notifications.eventOccurred(new GroupTrPostAcceptedEvent(group, false));
		notifications.eventOccurred(new GroupTrPostAcceptedEvent(group, false));
		idle();
		assertEquals(2, only(nm).number);

		notifications.eventOccurred(new GroupTrPostsRemovedEvent(group, one,
				0));
		idle();
		assertEquals("a read post leaves the count", 2, only(nm).number);

		notifications.eventOccurred(new GroupTrPostsRemovedEvent(group, one,
				1));
		idle();
		assertEquals(1, only(nm).number);

		notifications.eventOccurred(new GroupTrPostsRemovedEvent(group, one,
				1));
		idle();
		assertEquals(0, shadowOf(nm).getAllNotifications().size());
	}

	private static Notification only(NotificationManager nm) {
		List<Notification> all = shadowOf(nm).getAllNotifications();
		assertEquals(1, all.size());
		return all.get(0);
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}
}
