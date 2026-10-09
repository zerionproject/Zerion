package com.professor.zerion.android;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.app.api.messaging.event.MessageDeletedForEveryoneEvent;
import org.zerionproject.app.api.messaging.event.PrivateMessageReceivedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.core.api.system.Clock;

import java.util.Collections;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class WithdrawnMessageNotificationTest {

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

	private final ContactId contact = new ContactId(7);
	private final MessagingManager messaging = mock(MessagingManager.class);
	private NotificationManager system;
	private AndroidNotificationManagerImpl notifications;

	@Before
	public void setUp() {
		Application app = RuntimeEnvironment.getApplication();
		system = (NotificationManager)
				app.getSystemService(Context.NOTIFICATION_SERVICE);
		notifications = new AndroidNotificationManagerImpl(
				mock(SettingsManager.class), INLINE, app, mock(Clock.class),
				messaging, mock(ContactManager.class),
				mock(ConversationManager.class),
				mock(org.zerionproject.app.api.messaging
						.VoiceSignalFactory.class),
				app.getSharedPreferences("withdrawn-test",
						Context.MODE_PRIVATE),
				app.getSharedPreferences("withdrawn-test-profile",
						Context.MODE_PRIVATE));
	}

	private MessageId receive(boolean hasText) {
		MessageId m = new MessageId(getRandomId());
		PrivateMessageHeader h = new PrivateMessageHeader(m,
				new GroupId(getRandomId()), 1_000L, false, false, false, false,
				hasText, Collections.emptyList(), -1L);
		notifications.eventOccurred(new PrivateMessageReceivedEvent(h,
				contact));
		return m;
	}

	private void withdraw(MessageId m) {
		notifications.eventOccurred(new MessageDeletedForEveryoneEvent(
				contact, m, true));
	}

	private Notification shown() {
		int id = AndroidNotificationManagerImpl.CONTACT_NOTIFICATION_ID_BASE
				+ contact.getInt();
		for (android.service.notification.StatusBarNotification n :
				shadowOf(system).getActiveNotifications()) {
			if (n.getId() == id) return n.getNotification();
		}
		return null;
	}

	private int count() {
		Notification n = shown();
		return n == null ? 0 : n.number;
	}

	@Test
	public void aMessageDeletedForEveryoneLeavesNoNotification() {
		MessageId m = receive(false);
		assertEquals(1, count());

		withdraw(m);

		assertNull(shown());
	}

	@Test
	public void onlyTheWithdrawnMessageLeavesTheCount() {
		receive(false);
		receive(false);
		assertEquals(2, count());

		withdraw(new MessageId(getRandomId()));
		assertEquals("a message that was never counted changes nothing", 2,
				count());

		MessageId third = receive(false);
		withdraw(third);
		assertEquals(2, count());
	}

	@Test
	public void aMessageDeletedBeforeItsNotificationNeverShowsOne()
			throws Exception {
		MessageId gone = new MessageId(getRandomId());
		when(messaging.getMessageText(gone))
				.thenThrow(new NoSuchMessageException());
		PrivateMessageHeader h = new PrivateMessageHeader(gone,
				new GroupId(getRandomId()), 1_000L, false, false, false, false,
				true, Collections.emptyList(), -1L);
		notifications.eventOccurred(new PrivateMessageReceivedEvent(h,
				contact));

		assertNull(shown());
	}
}
