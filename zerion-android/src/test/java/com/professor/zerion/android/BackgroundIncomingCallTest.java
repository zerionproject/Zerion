package com.professor.zerion.android;

import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.service.notification.StatusBarNotification;

import com.professor.zerion.android.conversation.voice.IncomingCallRinger;
import com.professor.zerion.android.conversation.voice.VoiceCallActivity;
import com.professor.zerion.android.conversation.voice.VoiceCallKeyHolder;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.VoiceSignalFactory;
import org.zerionproject.app.api.messaging.VoiceSignalHeader;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.zerionproject.app.api.messaging.event.VoiceSignalReceivedEvent;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.core.api.system.Clock;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;

import javax.annotation.Nullable;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class BackgroundIncomingCallTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final long NOW = 1_700_000_000_000L;
	private static final String KEY_HEX =
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

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
	private final VoiceSignalFactory signals = mock(VoiceSignalFactory.class);
	private Application app;
	private NotificationManager system;
	private AndroidNotificationManagerImpl notifications;

	@Before
	public void setUp() throws Exception {
		app = RuntimeEnvironment.getApplication();
		system = (NotificationManager)
				app.getSystemService(Context.NOTIFICATION_SERVICE);
		Clock clock = mock(Clock.class);
		when(clock.currentTimeMillis()).thenReturn(NOW);
		ContactManager contacts = mock(ContactManager.class);
		when(contacts.getContact(any())).thenReturn(mock(Contact.class));
		MessagingManager messaging = mock(MessagingManager.class);
		when(messaging.getContactGroup(any())).thenReturn(mock(Group.class));
		notifications = new AndroidNotificationManagerImpl(
				mock(SettingsManager.class), INLINE, app, clock,
				messaging, contacts,
				mock(ConversationManager.class), signals,
				app.getSharedPreferences("call-test", Context.MODE_PRIVATE),
				app.getSharedPreferences("call-test-profile",
						Context.MODE_PRIVATE));
		while (shadowOf(app).getNextStartedActivity() != null) {
			shadowOf(app).getNextStartedActivity();
		}
	}

	@After
	public void tearDown() {
		VoiceCallKeyHolder.clear();
	}

	private void signal(VoiceSignalType type, String callId,
			@Nullable String payload) {
		signal(contact, type, callId, payload);
	}

	private void signal(ContactId from, VoiceSignalType type, String callId,
			@Nullable String payload) {
		VoiceSignalHeader h = new VoiceSignalHeader(
				new MessageId(getRandomId()), new GroupId(getRandomId()), NOW,
				false, type, callId, payload, null);
		notifications.eventOccurred(new VoiceSignalReceivedEvent(h, from));
	}

	@Nullable
	private Notification ringing() {
		for (StatusBarNotification n :
				shadowOf(system).getActiveNotifications()) {
			Notification x = n.getNotification();
			if (Notification.CATEGORY_CALL.equals(x.category)) return x;
		}
		return null;
	}

	@Nullable
	private Notification tagged(String tag, ContactId c) {
		for (StatusBarNotification n :
				shadowOf(system).getActiveNotifications()) {
			if (tag.equals(n.getTag()) && n.getId() == c.getInt()) {
				return n.getNotification();
			}
		}
		return null;
	}

	@Nullable
	private Notification missed(ContactId c) {
		return tagged(IncomingCallRinger.MISSED_TAG, c);
	}

	private static void advance(long ms) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
	}

	@Nullable
	private Intent nextStarted() {
		return shadowOf(app).getNextStartedActivity();
	}

	private static Activity openApp() {
		return Robolectric.buildActivity(Activity.class).setup().get();
	}

	@Test
	public void anOfferWhileTheAppIsInTheBackgroundRingsWithAFullScreenCall() {
		signal(VoiceSignalType.CALL_OFFER, "call-1", KEY_HEX);

		Notification ring = ringing();
		assertNotNull("a call started while Zerion is in the background "
				+ "must ring through a notification, the activity start "
				+ "alone is blocked by the system", ring);
		PendingIntent full = ring.fullScreenIntent;
		assertNotNull(full);
		Intent opens = shadowOf(full).getSavedIntent();
		assertEquals(VoiceCallActivity.class.getName(),
				opens.getComponent().getClassName());
		assertEquals(contact.getInt(),
				opens.getIntExtra(VoiceCallActivity.EXTRA_CONTACT_ID, -1));
		assertEquals("call-1",
				opens.getStringExtra(VoiceCallActivity.EXTRA_CALL_ID));
		assertTrue(opens.getBooleanExtra(
				VoiceCallActivity.EXTRA_IS_INCOMING, false));
		assertNull("no activity start from the background",
				shadowOf(app).getNextStartedActivity());
	}

	@Test
	public void openingTheAppWhileTheCallerStillRingsShowsTheCall() {
		signal(VoiceSignalType.CALL_OFFER, "call-1", KEY_HEX);
		assertNotNull(ringing());

		openApp();

		Intent shown = nextStarted();
		assertNotNull("opening Zerion while the caller rings must bring "
				+ "up the call", shown);
		assertEquals(VoiceCallActivity.class.getName(),
				shown.getComponent().getClassName());
		assertEquals("call-1",
				shown.getStringExtra(VoiceCallActivity.EXTRA_CALL_ID));
		assertTrue(shown.getBooleanExtra(
				VoiceCallActivity.EXTRA_IS_INCOMING, false));

		openApp();
		assertNull("the call is brought up once", nextStarted());
		assertNotNull("the offer is still held for the call screen",
				VoiceCallKeyHolder.consumeKey(contact.getInt(), "call-1"));
	}

	@Test
	public void anOfferWhileTheAppIsOnScreenOpensTheCallScreenAtOnce() {
		openApp();
		nextStarted();

		signal(VoiceSignalType.CALL_OFFER, "call-1", KEY_HEX);

		Intent shown = nextStarted();
		assertNotNull(shown);
		assertEquals(VoiceCallActivity.class.getName(),
				shown.getComponent().getClassName());
		assertEquals("call-1",
				shown.getStringExtra(VoiceCallActivity.EXTRA_CALL_ID));
		assertNull(ringing());
	}

	@Test
	public void theCallerGivingUpWhileItRingsLeavesAMissedCall() {
		signal(VoiceSignalType.CALL_OFFER, "call-1", KEY_HEX);
		assertNull(missed(contact));

		signal(VoiceSignalType.CALL_END, "call-1", null);

		assertNull(ringing());
		Notification m = missed(contact);
		assertNotNull("a call the user never saw must leave a trace", m);
		assertEquals(Notification.CATEGORY_MISSED_CALL, m.category);
		assertNull(VoiceCallKeyHolder.consumeKey(contact.getInt(), "call-1"));
	}

	@Test
	public void anEndForAnotherCallDoesNotStopTheRing() {
		signal(VoiceSignalType.CALL_OFFER, "call-1", KEY_HEX);

		signal(VoiceSignalType.CALL_END, "call-0", null);

		assertNotNull(ringing());
		assertNull(missed(contact));
	}

	@Test
	public void anUnansweredRingTimesOutIntoAMissedCall() {
		signal(VoiceSignalType.CALL_OFFER, "call-1", KEY_HEX);

		advance(IncomingCallRinger.RING_TIMEOUT_MS - 1_000);
		assertNotNull(ringing());
		assertNull(missed(contact));

		advance(2_000);
		assertNull(ringing());
		assertNotNull(missed(contact));
	}

	@Test
	public void theCallScreenTakingOverStopsTheRingWithoutAMissedCall() {
		signal(VoiceSignalType.CALL_OFFER, "call-1", KEY_HEX);

		notifications.callServiceStarted(contact, "call-1");

		assertNull(ringing());
		advance(IncomingCallRinger.RING_TIMEOUT_MS * 2);
		assertNull(missed(contact));
	}

	@Test
	public void decliningTheRingRejectsTheCallWithoutAMissedCall()
			throws Exception {
		signal(VoiceSignalType.CALL_OFFER, "call-1", KEY_HEX);

		notifications.declineRingingCall(contact, "call-1");

		assertNull(ringing());
		verify(signals).createCallReject(any(), anyLong(), eq("call-1"),
				isNull());
		assertNull(VoiceCallKeyHolder.consumeKey(contact.getInt(), "call-1"));
		advance(IncomingCallRinger.RING_TIMEOUT_MS * 2);
		assertNull(missed(contact));
	}

	@Test
	public void aStaleDeclineRejectsNothing() throws Exception {
		signal(VoiceSignalType.CALL_OFFER, "call-1", KEY_HEX);

		notifications.declineRingingCall(contact, "call-0");

		verify(signals, never()).createCallReject(any(), anyLong(), any(),
				any());
	}

	@Test
	public void anOfferDuringAnotherCallBecomesAMissedCall() throws Exception {
		ContactId other = new ContactId(8);
		notifications.callServiceStarted(other, "busy");

		signal(VoiceSignalType.CALL_OFFER, "call-1", KEY_HEX);

		assertNull(ringing());
		assertNull(nextStarted());
		assertNotNull(missed(contact));
		verify(signals).createCallBusy(any(), anyLong(), eq("call-1"));

		notifications.callServiceStopped();
		signal(other, VoiceSignalType.CALL_OFFER, "call-2", KEY_HEX);
		assertNotNull(tagged(IncomingCallRinger.RING_TAG, other));
	}

	@Test
	public void anEndThatOvertakesItsOfferNeverRings() {
		signal(VoiceSignalType.CALL_END, "call-1", null);

		signal(VoiceSignalType.CALL_OFFER, "call-1", KEY_HEX);

		assertNull(ringing());
		assertNotNull(missed(contact));
	}

	@Test
	public void aRingLeftOverFromAnEarlierRunIsClearedAtStart()
			throws Exception {
		Notification leftover = new androidx.core.app.NotificationCompat
				.Builder(app, "voice_call_ring")
				.setSmallIcon(com.professor.zerion.R.drawable.ic_phone_white)
				.build();
		system.notify(IncomingCallRinger.RING_TAG, contact.getInt(),
				leftover);
		system.notify("unrelated", 3, leftover);

		notifications.startService();

		assertNull(tagged(IncomingCallRinger.RING_TAG, contact));
		assertNotNull(tagged("unrelated", new ContactId(3)));
	}

	@Test
	public void theOldIncomingCallsChannelIsRemovedAtStart() throws Exception {
		system.createNotificationChannel(new android.app.NotificationChannel(
				"voice_call_channel", "Incoming calls",
				NotificationManager.IMPORTANCE_HIGH));

		notifications.startService();

		assertNull(system.getNotificationChannel("voice_call_channel"));
		assertNotNull(system.getNotificationChannel("voice_call_ring"));
		assertNotNull(system.getNotificationChannel("missed_call_channel"));
	}

	@Test
	public void theCallServiceIsKnownToTheCallScreenUntilItStops() {
		notifications.callServiceStarted(contact, "call-1");

		assertTrue(notifications.hasCall(contact, "call-1"));

		notifications.callServiceStopped();
		assertFalse(notifications.hasCall(contact, "call-1"));
	}

	@Test
	public void aCallScreenForAGoneCallEndsItsRing() {
		signal(VoiceSignalType.CALL_OFFER, "call-1", KEY_HEX);

		notifications.callUnavailable(contact, "call-1");

		assertNull(ringing());
		assertNotNull(missed(contact));
	}

	@Test
	public void signingOutStopsTheRingWithoutAMissedCall() throws Exception {
		signal(VoiceSignalType.CALL_OFFER, "call-1", KEY_HEX);

		notifications.stopService();

		assertNull(ringing());
		advance(IncomingCallRinger.RING_TIMEOUT_MS * 2);
		assertNull(missed(contact));
		assertNull(VoiceCallKeyHolder.consumeKey(contact.getInt(), "call-1"));
	}
}
