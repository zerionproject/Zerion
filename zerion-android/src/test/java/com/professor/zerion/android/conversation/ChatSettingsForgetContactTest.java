package com.professor.zerion.android.conversation;

import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.contact.ContactId;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChatSettingsForgetContactTest {

	static {
		TestAndroidKeyStore.register();
	}

	@After
	public void tearDown() {
		Context app = RuntimeEnvironment.getApplication();
		AppModule.getAndroidComponent(app).securePreferences().edit()
				.remove("mute_7").commit();
		app.getSharedPreferences("chat-settings-test", Context.MODE_PRIVATE)
				.edit().clear().commit();
	}

	@Test
	public void aRemovedContactsSettingsAreNotInherited() {
		Context app = RuntimeEnvironment.getApplication();
		SharedPreferences prefs = app.getSharedPreferences(
				"chat-settings-test", Context.MODE_PRIVATE);
		prefs.edit()
				.putBoolean("mute_7", true)
				.putBoolean("vibration_7", false)
				.putLong("timer_7", 3_600_000L)
				.putBoolean("mute_8", true)
				.commit();
		ContactId removed = new ContactId(7);

		ChatSettingsActivity.forgetContact(prefs, removed);

		assertFalse(ChatSettingsActivity.isContactMuted(prefs, removed));
		assertTrue(ChatSettingsActivity.isVibrationEnabled(prefs, removed));
		assertEquals(0, ChatSettingsActivity.getDisappearingTimer(prefs,
				removed));
		assertTrue("another contact keeps its settings",
				ChatSettingsActivity.isContactMuted(prefs, new ContactId(8)));
	}

	@Test
	public void aChatSettingKeptForTheWholeDeviceIsNotReadAsAProfiles() {
		Context app = RuntimeEnvironment.getApplication();
		AppModule.getAndroidComponent(app).securePreferences().edit()
				.putBoolean("mute_7", true).commit();

		assertFalse("contact 7 of this profile is not muted by it",
				ChatSettingsActivity.isContactMuted(app, new ContactId(7)));
	}
}
