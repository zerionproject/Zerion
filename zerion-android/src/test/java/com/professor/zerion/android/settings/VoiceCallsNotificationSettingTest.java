package com.professor.zerion.android.settings;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.os.Looper;
import android.provider.Settings;
import android.widget.CompoundButton;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VoiceCallsNotificationSettingTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String RING_CHANNEL = "voice_call_ring";

	private SettingsActivity activity;

	private NotificationsFragment host() {
		activity = Robolectric.buildActivity(SettingsActivity.class).setup()
				.get();
		NotificationsFragment fragment = new NotificationsFragment();
		activity.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, fragment).commitNow();
		idle();
		return fragment;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static NotificationManager system(Context c) {
		return (NotificationManager)
				c.getSystemService(Context.NOTIFICATION_SERVICE);
	}

	private static void channelSettingsExist(Context c) {
		ResolveInfo info = new ResolveInfo();
		info.activityInfo = new ActivityInfo();
		info.activityInfo.packageName = "com.android.settings";
		info.activityInfo.name = "ChannelNotificationSettings";
		shadowOf(c.getPackageManager()).addResolveInfoForIntent(
				new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS),
				info);
	}

	@Test
	public void theVoiceCallsSwitchOpensTheRingingCallsChannel() {
		NotificationsFragment fragment = host();
		channelSettingsExist(activity);
		while (shadowOf(activity).getNextStartedActivity() != null) {
			idle();
		}

		fragment.requireView().findViewById(R.id.notify_voice_calls_card)
				.performClick();

		Intent opened = shadowOf(activity).getNextStartedActivity();
		assertNotNull(opened);
		assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,
				opened.getAction());
		assertEquals("the voice calls switch must open the ringing channel",
				RING_CHANNEL,
				opened.getStringExtra(Settings.EXTRA_CHANNEL_ID));
	}

	@Test
	public void theVoiceCallsSwitchShowsTheRingingChannelState() {
		NotificationManager nm = system(
				androidx.test.core.app.ApplicationProvider
						.getApplicationContext());
		nm.createNotificationChannel(new NotificationChannel(RING_CHANNEL,
				"Ringing calls", NotificationManager.IMPORTANCE_NONE));
		nm.createNotificationChannel(new NotificationChannel(
				"CONTACT_CHANNEL_ID", "Contacts",
				NotificationManager.IMPORTANCE_DEFAULT));

		NotificationsFragment fragment = host();

		CompoundButton calls = fragment.requireView()
				.findViewById(R.id.notify_voice_calls_switch);
		CompoundButton messages = fragment.requireView()
				.findViewById(R.id.notify_private_messages_switch);
		assertFalse("calls are off when the ringing channel is off",
				calls.isChecked());
		assertTrue(messages.isChecked());
	}
}
