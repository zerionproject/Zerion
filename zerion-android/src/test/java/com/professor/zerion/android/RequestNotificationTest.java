package com.professor.zerion.android;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.service.notification.StatusBarNotification;

import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.contact.request.RequestsActivity;
import com.professor.zerion.android.profile.TestProfileSession;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowNotificationManager;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.event.ContactLinkFloodedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestAddedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestReceivedEvent;
import org.zerionproject.core.api.contact.request.event.ContactRequestWithdrawnEvent;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.core.api.system.Clock;

import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class RequestNotificationTest {

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

	private NotificationManager system;
	private AndroidNotificationManagerImpl notifications;

	@Before
	public void setUp() throws Exception {
		Application app = RuntimeEnvironment.getApplication();
		system = (NotificationManager)
				app.getSystemService(Context.NOTIFICATION_SERVICE);
		SettingsManager settings = mock(SettingsManager.class);
		when(settings.getSettings(any())).thenReturn(new Settings());
		notifications = new AndroidNotificationManagerImpl(settings, INLINE,
				app, mock(Clock.class), mock(MessagingManager.class),
				mock(ContactManager.class), mock(ConversationManager.class),
				mock(org.zerionproject.app.api.messaging
						.VoiceSignalFactory.class),
				app.getSharedPreferences("request-test",
						Context.MODE_PRIVATE),
				app.getSharedPreferences("request-test-profile",
						Context.MODE_PRIVATE));
		notifications.startService();
	}

	private void received() {
		notifications.eventOccurred(
				new ContactRequestReceivedEvent(new byte[16], false));
	}

	private Notification shown() {
		for (android.service.notification.StatusBarNotification n :
				shadowOf(system).getActiveNotifications()) {
			if (n.getId() == AndroidNotificationManagerImpl
					.CONTACT_REQUEST_NOTIFICATION_ID) {
				return n.getNotification();
			}
		}
		return null;
	}

	private static String text(Notification n) {
		return String.valueOf(n.extras.getCharSequence(
				Notification.EXTRA_TEXT));
	}

	@Test
	public void requestsHaveTheirOwnChannel() {
		NotificationChannel c = system.getNotificationChannel(
				AndroidNotificationManager.CONTACT_REQUEST_CHANNEL_ID);
		assertNotNull(c);
		assertEquals("Contact requests", String.valueOf(c.getName()));
		received();
		assertEquals(AndroidNotificationManager.CONTACT_REQUEST_CHANNEL_ID,
				shown().getChannelId());
	}

	@Test
	public void theTextIsFixedAndCountsRequests() {
		received();
		Notification n = shown();
		assertEquals("New contact request", text(n));
		assertEquals(1, n.number);
		received();
		received();
		n = shown();
		assertEquals("3 new contact requests", text(n));
		assertEquals(3, n.number);
	}

	@Test
	public void theNotificationCarriesNoRequestContent() {
		received();
		Notification n = shown();
		StringBuilder all = new StringBuilder();
		for (String key : n.extras.keySet()) {
			all.append(n.extras.get(key)).append('\n');
		}
		String s = all.toString();
		assertFalse(s, s.contains("via"));
		assertNull(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
		assertNull(n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
		assertEquals(0, n.actions == null ? 0 : n.actions.length);
	}

	@Test
	public void tappingOpensTheReceivedTabOfTheRequestsScreen() {
		received();
		Intent i = shadowOf(shown().contentIntent).getSavedIntent();
		assertEquals(RequestsActivity.class.getName(),
				i.getComponent().getClassName());
		assertEquals(RequestsActivity.TAB_RECEIVED,
				i.getIntExtra(RequestsActivity.EXTRA_TAB, -1));
	}

	@Test
	public void sentRequestsAndWithdrawsDoNotNotify() {
		notifications.eventOccurred(
				new ContactRequestReceivedEvent(new byte[16], true));
		notifications.eventOccurred(
				new ContactRequestWithdrawnEvent(new byte[16], false));
		assertNull(shown());
	}

	@Test
	public void swipingTheNotificationAwayResetsTheCount() {
		received();
		received();
		Intent delete = shadowOf(shown().deleteIntent).getSavedIntent();
		assertEquals(NotificationCleanupService.class.getName(),
				delete.getComponent().getClassName());
		assertEquals(com.professor.zerion.android.navdrawer.NavDrawerActivity
				.CONTACT_REQUEST_URI, delete.getData());
		notifications.clearContactRequestNotification();
		received();
		assertEquals("New contact request", text(shown()));
	}

	@Test
	public void theCleanupServiceClearsTheRequestCount() {
		NotificationCleanupService service = new NotificationCleanupService();
		AndroidNotificationManager manager =
				mock(AndroidNotificationManager.class);
		service.notificationManager = manager;
		service.onHandleIntent(new Intent().setData(
				com.professor.zerion.android.navdrawer.NavDrawerActivity
						.CONTACT_REQUEST_URI));
		org.mockito.Mockito.verify(manager).clearContactRequestNotification();
	}

	@Test
	public void nothingIsPostedWhileTheRequestsScreenIsOpen() {
		received();
		notifications.blockContactRequestNotifications();
		assertNull(shown());
		received();
		assertNull(shown());
		notifications.unblockContactRequestNotifications();
		received();
		assertEquals("New contact request", text(shown()));
	}

	@Test
	public void openingTheRequestsScreenClearsTheCount() {
		received();
		received();
		notifications.clearContactRequestNotification();
		assertNull(shown());
		received();
		assertEquals("New contact request", text(shown()));
		assertTrue(shown().number == 1);
	}

	private Notification flooded() {
		for (android.service.notification.StatusBarNotification n :
				shadowOf(system).getActiveNotifications()) {
			if (n.getId() == AndroidNotificationManagerImpl
					.LINK_FLOODED_NOTIFICATION_ID) {
				return n.getNotification();
			}
		}
		return null;
	}

	@Test
	public void aFloodedLinkNotifiesWithFixedTextOnTheRequestChannel() {
		notifications.eventOccurred(new ContactLinkFloodedEvent(new byte[32],
				ContactLink.KIND_PERMANENT));
		Notification n = flooded();
		assertNotNull(n);
		assertEquals(AndroidNotificationManager.CONTACT_REQUEST_CHANNEL_ID,
				n.getChannelId());
		assertEquals("Invite link paused: too many requests. It resumes by "
				+ "itself.", text(n));
		assertNull("the request count is untouched", shown());
		assertEquals(0, n.actions == null ? 0 : n.actions.length);
		Intent i = shadowOf(n.contentIntent).getSavedIntent();
		assertEquals(com.professor.zerion.android.contact.request
				.InviteLinksActivity.class.getName(),
				i.getComponent().getClassName());
	}

	@Test
	public void aFloodedZeroIdSaysItWasResetAndToShareTheNewOne() {
		notifications.eventOccurred(new ContactLinkFloodedEvent(new byte[32],
				ContactLink.KIND_ZERO_ID));
		Notification n = flooded();
		assertNotNull(n);
		assertEquals("Too many requests, so your ZerionID was reset. Share "
				+ "the new one.", text(n));
		StringBuilder all = new StringBuilder();
		for (String key : n.extras.keySet()) {
			all.append(n.extras.get(key)).append('\n');
		}
		assertFalse(all.toString().contains("via"));
	}

	private Notification accepted() {
		for (android.service.notification.StatusBarNotification n :
				shadowOf(system).getActiveNotifications()) {
			if (n.getId() == AndroidNotificationManagerImpl
					.REQUEST_ACCEPTED_NOTIFICATION_ID) {
				return n.getNotification();
			}
		}
		return null;
	}

	private void added(boolean outgoing, int contact) {
		notifications.eventOccurred(new ContactRequestAddedEvent(new byte[16],
				outgoing, new ContactId(contact)));
	}

	@Test
	public void theSenderIsToldItsRequestWasAcceptedWithoutAnyName() {
		added(true, 3);
		Notification n = accepted();
		assertNotNull(n);
		assertEquals(AndroidNotificationManager.CONTACT_REQUEST_CHANNEL_ID,
				n.getChannelId());
		assertEquals("Your contact request was accepted", text(n));
		assertEquals(0, n.actions == null ? 0 : n.actions.length);
		assertNull(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
		assertNull(n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
		assertNull("the request count is untouched", shown());
		Intent i = shadowOf(n.contentIntent).getSavedIntent();
		assertEquals(com.professor.zerion.android.conversation
				.ConversationActivity.class.getName(),
				i.getComponent().getClassName());
		assertEquals(3, i.getIntExtra(com.professor.zerion.android
				.conversation.ConversationActivity.CONTACT_ID, -1));
	}

	@Test
	public void theReceiverGetsNoAcceptedNotification() {
		added(false, 3);
		assertNull(accepted());
	}

	@Test
	public void noAcceptedNotificationWhileTheRequestsScreenIsOpen() {
		notifications.blockContactRequestNotifications();
		added(true, 3);
		assertNull(accepted());
		notifications.unblockContactRequestNotifications();
		added(true, 4);
		assertNotNull(accepted());
	}

	@Test
	public void aSecondAcceptedRequestOpensTheSentTab() {
		added(true, 3);
		added(true, 4);
		Notification n = accepted();
		assertEquals("Your contact request was accepted", text(n));
		Intent i = shadowOf(n.contentIntent).getSavedIntent();
		assertEquals(RequestsActivity.class.getName(),
				i.getComponent().getClassName());
		assertEquals(RequestsActivity.TAB_SENT,
				i.getIntExtra(RequestsActivity.EXTRA_TAB, -1));
	}

	@Test
	public void theAcceptedNotificationHidesItsTextOnTheLockScreen() {
		added(true, 3);
		Notification n = accepted();
		assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility);
		assertNotNull(n.publicVersion);
		StringBuilder all = new StringBuilder();
		for (String key : n.publicVersion.extras.keySet()) {
			all.append(n.publicVersion.extras.get(key)).append('\n');
		}
		assertFalse(all.toString(), all.toString().contains("request"));
		assertFalse(all.toString(), all.toString().contains("accepted"));
	}

	@Test
	public void openingThatChatClearsTheAcceptedNotification() {
		added(true, 3);
		notifications.clearContactNotification(new ContactId(4));
		assertNotNull(accepted());
		notifications.clearContactNotification(new ContactId(3));
		assertNull(accepted());
		added(true, 5);
		Intent i = shadowOf(accepted().contentIntent).getSavedIntent();
		assertEquals(com.professor.zerion.android.conversation
				.ConversationActivity.class.getName(),
				i.getComponent().getClassName());
		assertEquals(5, i.getIntExtra(com.professor.zerion.android
				.conversation.ConversationActivity.CONTACT_ID, -1));
	}

	@Test
	public void openingTheRequestsScreenClearsTheAcceptedNotification() {
		added(true, 3);
		notifications.blockContactRequestNotifications();
		assertNull(accepted());
		notifications.unblockContactRequestNotifications();
		added(true, 4);
		Intent i = shadowOf(accepted().contentIntent).getSavedIntent();
		assertEquals(4, i.getIntExtra(com.professor.zerion.android
				.conversation.ConversationActivity.CONTACT_ID, -1));
	}

	@Test
	public void swipingTheRequestCountAwayKeepsTheAcceptedNotification() {
		added(true, 3);
		notifications.clearContactRequestNotification();
		assertNotNull(accepted());
	}

	@Test
	public void noAcceptedNotificationForAMutedContact() {
		Context app = RuntimeEnvironment.getApplication();
		SharedPreferences prefs = TestProfileSession.signedInPreferences(app);
		assertTrue(prefs.edit().putBoolean("mute_3", true).commit());
		try {
			added(true, 3);
			assertNull(accepted());
			added(true, 4);
			assertNotNull(accepted());
		} finally {
			prefs.edit().remove("mute_3").commit();
		}
	}

	@Implements(NotificationManager.class)
	public static class FailingNotificationManager
			extends ShadowNotificationManager {

		static volatile boolean fail = false;

		@Override
		@Implementation
		public StatusBarNotification[] getActiveNotifications() {
			if (fail) throw new SecurityException();
			return super.getActiveNotifications();
		}
	}

	@Test
	@Config(sdk = 29, shadows = FailingNotificationManager.class)
	public void aPhoneThatCannotListNotificationsStillShowsTheAcceptedOne() {
		FailingNotificationManager.fail = true;
		try {
			added(true, 3);
		} finally {
			FailingNotificationManager.fail = false;
		}
		Notification n = accepted();
		assertNotNull(n);
		Intent i = shadowOf(n.contentIntent).getSavedIntent();
		assertEquals(3, i.getIntExtra(com.professor.zerion.android
				.conversation.ConversationActivity.CONTACT_ID, -1));
	}
}
