package com.professor.zerion.android;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.core.api.system.Clock;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import androidx.core.app.NotificationCompat;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SignOutNotificationsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final AndroidExecutor INLINE = new AndroidExecutor() {
		@Override
		public <V> Future<V> runOnBackgroundThread(Callable<V> c) {
			return runOnUiThread(c);
		}

		@Override
		public void runOnBackgroundThread(Runnable r) {
			r.run();
		}

		@Override
		public <V> Future<V> runOnUiThread(Callable<V> c) {
			FutureTask<V> f = new FutureTask<>(c);
			f.run();
			return f;
		}

		@Override
		public void runOnUiThread(Runnable r) {
			r.run();
		}
	};

	@Test
	public void signingOutLeavesNoNotificationOfTheProfile() throws Exception {
		Application app = RuntimeEnvironment.getApplication();
		NotificationManager nm = (NotificationManager)
				app.getSystemService(Context.NOTIFICATION_SERVICE);
		Notification channelPost = new NotificationCompat.Builder(app,
				"channels").setSmallIcon(android.R.drawable.ic_dialog_info)
				.setContentTitle("A new post").build();
		nm.notify(0x4a000123, channelPost);
		nm.notify("rotation-7", 0, channelPost);
		assertEquals(2, shadowOf(nm).getAllNotifications().size());

		AndroidNotificationManagerImpl impl =
				new AndroidNotificationManagerImpl(mock(SettingsManager.class),
						INLINE, app, mock(Clock.class),
						mock(MessagingManager.class),
						mock(ContactManager.class),
						mock(ConversationManager.class),
						mock(org.zerionproject.app.api.messaging
								.VoiceSignalFactory.class),
						app.getSharedPreferences("sign-out-test",
								Context.MODE_PRIVATE),
						app.getSharedPreferences("sign-out-test-profile",
								Context.MODE_PRIVATE));
		impl.stopService();

		assertEquals(0, shadowOf(nm).getAllNotifications().size());
	}

	@Test
	public void theSignedInNotificationHasItsOwnGroup() throws Exception {
		Application app = RuntimeEnvironment.getApplication();
		NotificationManager nm = (NotificationManager)
				app.getSystemService(Context.NOTIFICATION_SERVICE);
		AndroidNotificationManagerImpl impl =
				new AndroidNotificationManagerImpl(mock(SettingsManager.class),
						INLINE, app, mock(Clock.class),
						mock(MessagingManager.class),
						mock(ContactManager.class),
						mock(ConversationManager.class),
						mock(org.zerionproject.app.api.messaging
								.VoiceSignalFactory.class),
						app.getSharedPreferences("group-test",
								Context.MODE_PRIVATE),
						app.getSharedPreferences("group-test-profile",
								Context.MODE_PRIVATE));
		impl.updateForegroundNotification(false);
		boolean found = false;
		for (Notification n : shadowOf(nm).getAllNotifications()) {
			if (AndroidNotificationManagerImpl.SESSION_GROUP.equals(
					n.getGroup())) {
				found = true;
			}
		}
		assertEquals("the signed-in notification carries its own group",
				true, found);
	}
}
