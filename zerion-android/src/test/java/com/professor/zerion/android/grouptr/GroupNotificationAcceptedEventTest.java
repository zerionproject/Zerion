package com.professor.zerion.android.grouptr;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Looper;

import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.event.GroupTrPostAcceptedEvent;
import org.zerionproject.core.api.event.EventListener;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupNotificationAcceptedEventTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Test
	public void anAcceptedPostOfAnotherMemberRaisesANotification() {
		ZerionApplication app = ApplicationProvider.getApplicationContext();
		EventListener notifications = (EventListener)
				app.getApplicationComponent().androidNotificationManager();
		Context ctx = ApplicationProvider.getApplicationContext();
		NotificationManager nm = (NotificationManager) ctx.getSystemService(
				Context.NOTIFICATION_SERVICE);

		notifications.eventOccurred(
				new GroupTrPostAcceptedEvent(new byte[32], true));
		shadowOf(Looper.getMainLooper()).idle();
		assertEquals(0, shadowOf(nm).getAllNotifications().size());

		notifications.eventOccurred(
				new GroupTrPostAcceptedEvent(new byte[32], false));
		shadowOf(Looper.getMainLooper()).idle();
		assertEquals(1, shadowOf(nm).getAllNotifications().size());
	}
}
