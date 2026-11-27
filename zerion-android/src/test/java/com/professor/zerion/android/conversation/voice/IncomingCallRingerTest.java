package com.professor.zerion.android.conversation.voice;

import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Looper;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.settings.AppIconManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class IncomingCallRingerTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String CALL_STYLE =
			"android.app.Notification$CallStyle";

	private Application app;
	private NotificationManager system;
	private SharedPreferences prefs;
	private final List<String> rejected = new ArrayList<>();
	private final List<String> busy = new ArrayList<>();

	@Before
	public void setUp() {
		app = ApplicationProvider.getApplicationContext();
		system = (NotificationManager)
				app.getSystemService(Context.NOTIFICATION_SERVICE);
		prefs = app.getSharedPreferences("ringer-test", Context.MODE_PRIVATE);
	}

	@After
	public void tearDown() {
		VoiceCallKeyHolder.clear();
		AppIconManager.setAppIcon(app, AppIconManager.ICON_DEFAULT);
	}

	private IncomingCallRinger.Rejecter rejecter() {
		return new IncomingCallRinger.Rejecter() {
			@Override
			public void reject(int contactId, String callId) {
				rejected.add(contactId + "/" + callId);
			}

			@Override
			public void busy(int contactId, String callId) {
				busy.add(contactId + "/" + callId);
			}
		};
	}

	private IncomingCallRinger ringer(boolean fullScreenAllowed) {
		return new IncomingCallRinger(app, system, prefs, rejecter(),
				() -> fullScreenAllowed);
	}

	@Nullable
	private Notification tagged(String tag, int id) {
		for (StatusBarNotification n :
				shadowOf(system).getActiveNotifications()) {
			if (tag.equals(n.getTag()) && n.getId() == id) {
				return n.getNotification();
			}
		}
		return null;
	}

	private static void advance(long ms) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
	}

	@Test
	public void theRingIsAnUrgentInsistentCallWithAnswerAndDecline() {
		ringer(true).offer(7, "call-1", false);

		Notification ring = tagged(IncomingCallRinger.RING_TAG, 7);
		assertNotNull(ring);
		assertEquals(Notification.CATEGORY_CALL, ring.category);
		assertEquals(Notification.PRIORITY_MAX, ring.priority);
		assertNotNull(ring.fullScreenIntent);
		assertTrue((ring.flags & Notification.FLAG_INSISTENT) != 0);
		assertTrue((ring.flags & Notification.FLAG_ONGOING_EVENT) != 0);
		assertEquals(2, ring.actions.length);
		Intent decline = shadowOf(ring.actions[1].actionIntent)
				.getSavedIntent();
		assertEquals(CallIntents.ACTION_DECLINE_CALL, decline.getAction());
		assertEquals(7, decline.getIntExtra(
				VoiceCallActivity.EXTRA_CONTACT_ID, -1));
		assertEquals("call-1",
				decline.getStringExtra(VoiceCallActivity.EXTRA_CALL_ID));
		Intent answer = shadowOf(ring.actions[0].actionIntent)
				.getSavedIntent();
		assertTrue(answer.getBooleanExtra(VoiceCallActivity.EXTRA_ANSWER,
				false));
	}

	@Test
	public void theRingRingsOnARingtoneChannelAndShowsOnlyAGenericLockScreenLine() {
		ringer(true).offer(7, "call-1", false);

		Notification ring = tagged(IncomingCallRinger.RING_TAG, 7);
		assertNotNull(ring);
		assertEquals(CallNotification.RING_CHANNEL_ID, ring.getChannelId());
		assertEquals(Notification.VISIBILITY_PRIVATE, ring.visibility);
		Notification shown = ring.publicVersion;
		assertNotNull("the lock screen shows a generic line", shown);
		assertEquals("Incoming call",
				shown.extras.getCharSequence(Notification.EXTRA_TITLE)
						.toString());
		assertNull(shown.extras.getCharSequence(Notification.EXTRA_TEXT));

		NotificationChannel channel = system.getNotificationChannel(
				CallNotification.RING_CHANNEL_ID);
		assertNotNull(channel);
		assertEquals(NotificationManager.IMPORTANCE_HIGH,
				channel.getImportance());
		assertEquals(Notification.VISIBILITY_PRIVATE,
				channel.getLockscreenVisibility());
		assertEquals(RingtoneManager.getDefaultUri(
				RingtoneManager.TYPE_RINGTONE), channel.getSound());
		assertEquals(AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
				channel.getAudioAttributes().getUsage());
	}

	@Test
	public void theRingNotificationExpiresWithTheRing() {
		ringer(true).offer(7, "call-1", false);

		Notification ring = tagged(IncomingCallRinger.RING_TAG, 7);
		assertNotNull(ring);
		assertEquals(IncomingCallRinger.RING_TIMEOUT_MS,
				ring.getTimeoutAfter());
	}

	@Test
	public void aVideoOfferOpensTheCallScreenAsVideo() {
		ringer(true).offer(7, "call-1", true);

		Notification ring = tagged(IncomingCallRinger.RING_TAG, 7);
		assertNotNull(ring);
		Intent opens = shadowOf(ring.fullScreenIntent).getSavedIntent();
		assertTrue(opens.getBooleanExtra(CallIntents.EXTRA_AUTO_VIDEO,
				false));
	}

	@Test
	@Config(sdk = 32)
	public void fromAndroid12TheRingUsesTheCallStyle() {
		ringer(true).offer(7, "call-1", false);

		Notification ring = tagged(IncomingCallRinger.RING_TAG, 7);
		assertNotNull(ring);
		assertEquals(CALL_STYLE,
				ring.extras.getString(Notification.EXTRA_TEMPLATE));
		assertNotNull(ring.fullScreenIntent);
		assertFalse(ring.extras.getBoolean(Notification.EXTRA_CALL_IS_VIDEO));
	}

	@Test
	@Config(sdk = 32)
	public void underTheCallStyleAnswerAndDeclineReachTheCall() {
		ringer(true).offer(7, "call-1", false);

		Notification ring = tagged(IncomingCallRinger.RING_TAG, 7);
		assertNotNull(ring);
		PendingIntent answer = ring.extras.getParcelable(
				Notification.EXTRA_ANSWER_INTENT);
		assertNotNull(answer);
		Intent opens = shadowOf(answer).getSavedIntent();
		assertEquals(VoiceCallActivity.class.getName(),
				opens.getComponent().getClassName());
		assertTrue(opens.getBooleanExtra(VoiceCallActivity.EXTRA_ANSWER,
				false));
		assertEquals("call-1",
				opens.getStringExtra(VoiceCallActivity.EXTRA_CALL_ID));
		PendingIntent decline = ring.extras.getParcelable(
				Notification.EXTRA_DECLINE_INTENT);
		assertNotNull(decline);
		assertEquals(CallIntents.ACTION_DECLINE_CALL,
				shadowOf(decline).getSavedIntent().getAction());
	}

	@Test
	@Config(sdk = 32)
	public void aVideoRingIsMarkedAsVideoUnderTheCallStyle() {
		ringer(true).offer(7, "call-1", true);

		Notification ring = tagged(IncomingCallRinger.RING_TAG, 7);
		assertNotNull(ring);
		assertTrue(ring.extras.getBoolean(Notification.EXTRA_CALL_IS_VIDEO));
	}

	@Test
	@Config(sdk = 32)
	public void aDisguisedAppRingsWithoutRevealingAZerionCall() {
		AppIconManager.setAppIcon(app, AppIconManager.ICON_CALCULATOR);

		ringer(true).offer(7, "call-1", false);

		Notification ring = tagged(IncomingCallRinger.RING_TAG, 7);
		assertNotNull(ring);
		assertNull("no call style while disguised",
				ring.extras.getString(Notification.EXTRA_TEMPLATE));
		assertEquals(Notification.CATEGORY_CALL, ring.category);
		assertNotNull(ring.fullScreenIntent);
		assertEquals(2, ring.actions.length);
		String title = ring.extras.getCharSequence(Notification.EXTRA_TITLE)
				.toString();
		assertNotEquals("Incoming call", title);
		assertNotEquals("Secure voice call", ring.extras.getCharSequence(
				Notification.EXTRA_TEXT).toString());
		assertEquals(title, ring.publicVersion.extras.getCharSequence(
				Notification.EXTRA_TITLE).toString());
	}

	@Test
	@Config(sdk = 32)
	public void aRefusedCallStyleFallsBackToAPlainRing() {
		NotificationManager refusing = mock(NotificationManager.class);
		doThrow(new IllegalArgumentException()).when(refusing).notify(
				eq(IncomingCallRinger.RING_TAG), eq(7),
				argThat(n -> n != null && CALL_STYLE.equals(
						n.extras.getString(Notification.EXTRA_TEMPLATE))));
		IncomingCallRinger r = new IncomingCallRinger(app, refusing, prefs,
				rejecter(), () -> true);

		r.offer(7, "call-1", false);

		verify(refusing, times(2)).notify(eq(IncomingCallRinger.RING_TAG),
				eq(7), any(Notification.class));
		verify(refusing).notify(eq(IncomingCallRinger.RING_TAG), eq(7),
				argThat(n -> n != null && n.fullScreenIntent != null
						&& n.extras.getString(Notification.EXTRA_TEMPLATE)
						== null && n.actions.length == 2));
		assertTrue(r.isRinging(7, "call-1"));
	}

	@Test
	public void fromAndroid14TheHintOpensTheFullScreenPermissionPage() {
		Intent settings = IncomingCallRinger.fullScreenSettings(app, 34);

		assertEquals("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT",
				settings.getAction());
		assertEquals("package:" + app.getPackageName(),
				settings.getDataString());
		assertTrue((settings.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK)
				!= 0);

		Intent older = IncomingCallRinger.fullScreenSettings(app, 33);
		assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,
				older.getAction());
	}

	@Test
	public void theSettingsHintStandsAloneSoTheSystemCannotBundleIt() {
		IncomingCallRinger r = ringer(false);
		r.offer(7, "call-1", false);
		r.remoteEnded(7, "call-1");

		Notification hint = tagged(IncomingCallRinger.HINT_TAG, 0);
		assertNotNull(hint);
		assertEquals(IncomingCallRinger.HINT_TAG, hint.getGroup());
		assertTrue("the hint is its own group",
				(hint.flags & Notification.FLAG_GROUP_SUMMARY) != 0);
		Notification missed = tagged(IncomingCallRinger.MISSED_TAG, 7);
		assertNotNull(missed);
		assertNotEquals(hint.getGroup(), missed.getGroup());
	}

	@Test
	public void fromAndroid14TappingTheHintOpensTheFullScreenPermissionPage() {
		new IncomingCallRinger(app, system, prefs, rejecter(), () -> false, 34)
				.offer(7, "call-1", false);

		Notification hint = tagged(IncomingCallRinger.HINT_TAG, 0);
		assertNotNull(hint);
		assertTrue(shadowOf(hint.contentIntent).isActivityIntent());
		Intent opens = shadowOf(hint.contentIntent).getSavedIntent();
		assertEquals("android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT",
				opens.getAction());
		assertEquals("package:" + app.getPackageName(), opens.getDataString());
		assertNull("the system settings page, not a Zerion screen",
				opens.getComponent());
	}

	@Test
	public void withFullScreenAllowedNoSettingsHintIsShown() {
		ringer(true).offer(7, "call-1", false);

		assertNull(tagged(IncomingCallRinger.HINT_TAG, 0));
	}

	@Test
	public void withoutFullScreenTheCallStillRingsAndSettingsAreOfferedOnce() {
		IncomingCallRinger r = ringer(false);
		r.offer(7, "call-1", false);

		assertNotNull("the call still rings as an urgent notification",
				tagged(IncomingCallRinger.RING_TAG, 7));
		Notification hint = tagged(IncomingCallRinger.HINT_TAG, 0);
		assertNotNull(hint);
		PendingIntent open = hint.contentIntent;
		Intent settings = shadowOf(open).getSavedIntent();
		assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS,
				settings.getAction());
		assertEquals(app.getPackageName(),
				settings.getStringExtra(Settings.EXTRA_APP_PACKAGE));

		system.cancel(IncomingCallRinger.HINT_TAG, 0);
		r.decline(7, "call-1");
		r.offer(8, "call-2", false);
		assertNotNull(tagged(IncomingCallRinger.RING_TAG, 8));
		assertNull("the settings are offered only once",
				tagged(IncomingCallRinger.HINT_TAG, 0));
		assertEquals(1, rejected.size());
	}

	@Test
	public void aSecondCallerWhileOneRingsIsToldBusyAndTheFirstKeepsRinging() {
		IncomingCallRinger r = ringer(true);
		r.offer(7, "call-1", false);

		r.offer(8, "call-2", false);

		assertTrue(r.isRinging(7, "call-1"));
		assertNotNull(tagged(IncomingCallRinger.RING_TAG, 7));
		assertNull(tagged(IncomingCallRinger.RING_TAG, 8));
		assertEquals(1, busy.size());
		assertEquals("8/call-2", busy.get(0));
		assertNotNull(tagged(IncomingCallRinger.MISSED_TAG, 8));
		assertNull(tagged(IncomingCallRinger.MISSED_TAG, 7));
	}

	@Test
	public void aRedialFromTheRingingCallerReplacesItsOldRing() {
		IncomingCallRinger r = ringer(true);
		r.offer(7, "call-1", false);

		r.offer(7, "call-2", false);

		assertTrue(r.isRinging(7, "call-2"));
		assertTrue(busy.isEmpty());
	}

	@Test
	public void anotherCallStartingEndsTheRingAndItsTimer() {
		IncomingCallRinger r = ringer(true);
		r.offer(7, "call-1", false);

		r.callStarted(8, "outgoing");

		assertFalse(r.isRinging(7, "call-1"));
		assertNull(tagged(IncomingCallRinger.RING_TAG, 7));
		assertNotNull(tagged(IncomingCallRinger.MISSED_TAG, 7));
		system.cancel(IncomingCallRinger.MISSED_TAG, 7);
		advance(IncomingCallRinger.RING_TIMEOUT_MS * 2);
		assertNull("the old ring timer is gone",
				tagged(IncomingCallRinger.MISSED_TAG, 7));
	}

	@Test
	public void theRunningCallIsKnownUntilItStops() {
		IncomingCallRinger r = ringer(true);
		r.callStarted(7, "call-1");

		assertTrue(r.hasCall(7, "call-1"));
		assertFalse(r.hasCall(7, "call-0"));
		assertFalse(r.hasCall(8, "call-1"));

		r.callStopped();
		assertFalse(r.hasCall(7, "call-1"));
	}

	@Test
	public void withCallAlertsOffACallOnlyLeavesASilentMissedCall() {
		prefs.edit().putBoolean(
				AndroidNotificationManager.PREF_NOTIFY_VOICE_CALLS, false)
				.commit();
		IncomingCallRinger r = ringer(false);

		r.offer(7, "call-1", false);

		assertNull("no ring", tagged(IncomingCallRinger.RING_TAG, 7));
		assertNull("no settings hint", tagged(IncomingCallRinger.HINT_TAG, 0));
		assertTrue("opening the app still brings up the call",
				r.isRinging(7, "call-1"));
		assertNull(tagged(IncomingCallRinger.MISSED_TAG, 7));

		advance(IncomingCallRinger.RING_TIMEOUT_MS + 1_000);

		Notification missed = tagged(IncomingCallRinger.MISSED_TAG, 7);
		assertNotNull(missed);
		assertEquals(Notification.GROUP_ALERT_SUMMARY,
				missed.getGroupAlertBehavior());
	}

	@Test
	public void aBlockedRingingChannelCountsAsCallAlertsOff() {
		system.createNotificationChannel(new NotificationChannel(
				CallNotification.RING_CHANNEL_ID, "Ringing calls",
				NotificationManager.IMPORTANCE_NONE));
		IncomingCallRinger r = ringer(true);

		r.offer(7, "call-1", false);

		assertNull("no ring", tagged(IncomingCallRinger.RING_TAG, 7));
		assertTrue(r.isRinging(7, "call-1"));
		advance(IncomingCallRinger.RING_TIMEOUT_MS + 1_000);
		Notification missed = tagged(IncomingCallRinger.MISSED_TAG, 7);
		assertNotNull(missed);
		assertEquals(Notification.GROUP_ALERT_SUMMARY,
				missed.getGroupAlertBehavior());
	}

	@Test
	public void withCallAlertsOnTheMissedCallMayAlert() {
		IncomingCallRinger r = ringer(true);
		r.offer(7, "call-1", false);

		advance(IncomingCallRinger.RING_TIMEOUT_MS + 1_000);

		Notification missed = tagged(IncomingCallRinger.MISSED_TAG, 7);
		assertNotNull(missed);
		assertNotEquals(Notification.GROUP_ALERT_SUMMARY,
				missed.getGroupAlertBehavior());
	}

	@Test
	public void signingOutForgetsEveryHeldOffer() {
		VoiceCallKeyHolder.setOffer(9, "other",
				new org.zerionproject.core.api.crypto.SecretKey(
						new byte[32]), null);
		IncomingCallRinger r = ringer(true);
		r.offer(7, "call-1", false);

		r.clear();

		assertFalse(VoiceCallKeyHolder.isHeld(9, "other"));
		assertFalse(r.isRinging(7, "call-1"));
		assertNull(tagged(IncomingCallRinger.RING_TAG, 7));
	}
}
