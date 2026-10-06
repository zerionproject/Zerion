package com.professor.zerion.android.profile;

import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.util.IoUtils;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.profile.ProfileStorageTestSupport.addProfile;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.deviceSecure;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.deviceUi;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.keyOf;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.notSignedIn;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.profileDir;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.signedIn;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ProfileStorageTest {

	static {
		TestAndroidKeyStore.register();
	}

	private Context app;

	@Before
	public void setUp() {
		app = RuntimeEnvironment.getApplication();
		com.professor.zerion.android.AppModule.getAndroidComponent(app)
				.accountManager();
		deviceSecure(app).edit().clear().commit();
		deviceUi(app).edit().clear().commit();
	}

	@After
	public void tearDown() {
		IoUtils.deleteFileOrDir(new File(app.getFilesDir(), "profiles"));
		IoUtils.deleteFileOrDir(new File(app.getFilesDir(), "stickers"));
		app.getSharedPreferences("chat_settings", Context.MODE_PRIVATE).edit()
				.clear().commit();
		deviceSecure(app).edit().clear().commit();
		deviceUi(app).edit().clear().commit();
	}

	@Test
	public void eachProfileSeesOnlyItsOwnSettings() throws Exception {
		addProfile(app, "default");
		addProfile(app, "b7c1");

		signedIn(app, "default").preferences().edit()
				.putBoolean("mute_7", true).commit();

		SharedPreferences hidden = signedIn(app, "b7c1").preferences();
		assertFalse(hidden.getBoolean("mute_7", false));
		assertTrue(hidden.edit().putBoolean("vibration_7", false).commit());

		SharedPreferences decoy = signedIn(app, "default").preferences();
		assertTrue("kept across processes", decoy.getBoolean("mute_7", false));
		assertTrue(decoy.getBoolean("vibration_7", true));
		assertEquals(Collections.singleton("mute_7"), decoy.getAll().keySet());
	}

	@Test
	public void theSettingsFileOpensOnlyWithItsProfilesKey() throws Exception {
		addProfile(app, "default");
		signedIn(app, "default").preferences().edit()
				.putString("channel_draft_ab", "meet at noon").commit();
		File file = new File(profileDir(app, "default"),
				ProfileStorage.SETTINGS_FILE);
		String raw = new String(Files.readAllBytes(file.toPath()),
				StandardCharsets.ISO_8859_1);

		assertFalse(raw.contains("meet at noon"));
		assertFalse(raw.contains("channel_draft_ab"));
		byte[] wrong = ProfileStorageTestSupport.sha256(
				"another key".getBytes(StandardCharsets.UTF_8));
		assertTrue(ProfileSettingsFile.open(file, wrong, Runnable::run)
				.getAll().isEmpty());
		byte[] right = ProfileStorageTestSupport.crypto().deriveKey(
				"org.zerionproject/PROFILE_SETTINGS_KEY", keyOf("default"))
				.getBytes();
		assertEquals("meet at noon", ProfileSettingsFile.open(file, right,
				Runnable::run).getString("channel_draft_ab", null));
	}

	@Test
	public void beforeSignInNothingIsReadOrWritten() throws Exception {
		addProfile(app, "default");
		signedIn(app, "default").preferences().edit()
				.putBoolean("mute_7", true).commit();

		SharedPreferences prefs = notSignedIn(app).preferences();

		assertFalse(prefs.getBoolean("mute_7", false));
		assertTrue(prefs.getAll().isEmpty());
		assertFalse(prefs.edit().putBoolean("mute_8", true).commit());
		assertFalse(signedIn(app, "default").preferences()
				.contains("mute_8"));
	}

	@Test
	public void withOneProfileEveryDeviceWideEntryMovesIntoIt()
			throws Exception {
		addProfile(app, "default");
		deviceSecure(app).edit()
				.putBoolean("mute_7", true)
				.putString("channel_draft_ab", "draft")
				.putInt("autolock_timeout", 5)
				.putString("pref_key_theme", "dark")
				.commit();
		deviceUi(app).edit()
				.putStringSet("pinned_contact_ids",
						Collections.singleton("7"))
				.putBoolean("pref_key_screenshot_protection", false)
				.commit();
		app.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
				.edit().putLong("timer_9", 60L).commit();

		SharedPreferences decoy = signedIn(app, "default").preferences();
		assertTrue(decoy.getBoolean("mute_7", false));
		assertEquals("draft", decoy.getString("channel_draft_ab", null));
		assertEquals(5, decoy.getInt("autolock_timeout", 60));
		assertEquals(Collections.singleton("7"),
				decoy.getStringSet("pinned_contact_ids", null));
		assertEquals(60L, decoy.getLong("timer_9", 0));
		assertEquals("device settings stay on the device",
				Collections.singleton("pref_key_theme"),
				deviceSecure(app).getAll().keySet());
		assertEquals(Collections.singleton("pref_key_screenshot_protection"),
				deviceUi(app).getAll().keySet());
		assertFalse("the old plain file is gone", new File(new File(
				app.getApplicationInfo().dataDir, "shared_prefs"),
				"chat_settings.xml").exists());
	}

	@Test
	public void withSeveralProfilesContactEntriesPinsAndDraftsAreDropped()
			throws Exception {
		addProfile(app, "default");
		addProfile(app, "b7c1");
		deviceSecure(app).edit()
				.putBoolean("mute_7", true)
				.putBoolean("vibration_7", false)
				.putLong("timer_7", 60L)
				.putString("channel_draft_ab", "draft")
				.putString("channel_comment_draft_ab", "comment")
				.putInt("autolock_timeout", 5)
				.putString("pref_key_theme", "dark")
				.commit();
		deviceUi(app).edit()
				.putStringSet("pinned_contact_ids",
						Collections.singleton("7"))
				.commit();
		app.getSharedPreferences("chat_settings", Context.MODE_PRIVATE)
				.edit().putLong("timer_9", 60L).commit();

		SharedPreferences hidden = signedIn(app, "b7c1").preferences();
		assertTrue("the hidden profile gets nothing",
				hidden.getAll().isEmpty());
		assertTrue(deviceSecure(app).contains("mute_7"));

		SharedPreferences decoy = signedIn(app, "default").preferences();
		assertEquals("only the vault settings move",
				Collections.singleton("autolock_timeout"),
				decoy.getAll().keySet());
		assertEquals(5, decoy.getInt("autolock_timeout", 60));
		assertEquals("device settings stay on the device",
				Collections.singleton("pref_key_theme"),
				deviceSecure(app).getAll().keySet());
		assertTrue(deviceUi(app).getAll().isEmpty());
		assertFalse("the old plain file is gone", new File(new File(
				app.getApplicationInfo().dataDir, "shared_prefs"),
				"chat_settings.xml").exists());
		assertTrue(signedIn(app, "b7c1").preferences().getAll().isEmpty());
	}

	@Test
	public void theMessagingTogglesOfAnEarlierVersionMoveIntoTheOwner()
			throws Exception {
		addProfile(app, "default");
		addProfile(app, "b7c1");
		deviceUi(app).edit()
				.putBoolean("pref_typing_indicators", false)
				.putBoolean("voice_calls_enabled", false)
				.putBoolean("video_calls_enabled", true)
				.putBoolean("pref_key_notify_quick_reply", false)
				.putLong("default_disappearing_timer", 86400000L)
				.putBoolean("pref_key_screenshot_protection", false)
				.commit();

		assertTrue(signedIn(app, "b7c1").preferences().getAll().isEmpty());
		SharedPreferences decoy = signedIn(app, "default").preferences();

		assertFalse(decoy.getBoolean("pref_typing_indicators", true));
		assertFalse(decoy.getBoolean("voice_calls_enabled", true));
		assertTrue(decoy.getBoolean("video_calls_enabled", false));
		assertFalse(decoy.getBoolean("pref_key_notify_quick_reply", true));
		assertEquals(86400000L,
				decoy.getLong("default_disappearing_timer", -1L));
		assertEquals(Collections.singleton("pref_key_screenshot_protection"),
				deviceUi(app).getAll().keySet());
	}

	@Test
	public void aMoveCutShortCompletesWithoutOverwriting() throws Exception {
		addProfile(app, "default");
		signedIn(app, "default").preferences().edit()
				.putBoolean("mute_7", false).commit();
		deviceSecure(app).edit().putBoolean("mute_7", true)
				.putBoolean("mute_8", true).commit();

		SharedPreferences decoy = signedIn(app, "default").preferences();

		assertFalse("the profile's own value wins",
				decoy.getBoolean("mute_7", true));
		assertTrue(decoy.getBoolean("mute_8", false));
		assertTrue(deviceSecure(app).getAll().isEmpty());
		signedIn(app, "default");
		assertTrue(signedIn(app, "default").preferences()
				.getBoolean("mute_8", false));
	}

	@Test
	public void withoutTheDefaultProfileTheFirstToSignInTakesTheData()
			throws Exception {
		addProfile(app, "a1");
		addProfile(app, "b2");
		deviceSecure(app).edit().putInt("autolock_timeout", 5)
				.putBoolean("mute_7", true).commit();

		SharedPreferences first = signedIn(app, "b2").preferences();
		assertEquals(5, first.getInt("autolock_timeout", 60));
		assertFalse("a contact's entry is dropped with several profiles",
				first.contains("mute_7"));
		assertTrue(deviceSecure(app).getAll().isEmpty());
		assertTrue(signedIn(app, "a1").preferences().getAll().isEmpty());
	}

	@Test
	public void stickersArePerProfileAndTheDeviceSetGoesToTheOnlyProfile()
			throws Exception {
		addProfile(app, "default");
		File deviceStickers = new File(app.getFilesDir(), "stickers");
		assertTrue(deviceStickers.mkdirs());
		Files.write(new File(deviceStickers, "s1.bin").toPath(),
				new byte[] {1, 2, 3});

		File decoy = signedIn(app, "default").stickerDir();
		assertTrue(new File(decoy, "s1.bin").exists());
		assertFalse(deviceStickers.exists());
		assertTrue(decoy.getPath().startsWith(
				profileDir(app, "default").getPath()));

		addProfile(app, "b7c1");
		File hidden = signedIn(app, "b7c1").stickerDir();
		assertEquals(0, hidden.list().length);
		assertNotEquals(hidden, decoy);
	}

	@Test
	public void withSeveralProfilesAndNoSharedVaultTheDeviceStickersAreDropped()
			throws Exception {
		addProfile(app, "default");
		addProfile(app, "b7c1");
		File deviceStickers = new File(app.getFilesDir(), "stickers");
		assertTrue(deviceStickers.mkdirs());
		Files.write(new File(deviceStickers, "s1.bin").toPath(),
				new byte[] {1, 2, 3});

		File hidden = signedIn(app, "b7c1").stickerDir();
		assertEquals(0, hidden.list().length);
		assertTrue("nothing moves before the owner signs in",
				deviceStickers.exists());

		File decoy = signedIn(app, "default").stickerDir();
		assertEquals(0, decoy.list().length);
		assertFalse(deviceStickers.exists());
	}
}
