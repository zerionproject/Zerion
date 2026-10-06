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
import java.nio.file.Paths;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.profile.ProfileStorageTestSupport.addProfile;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.deviceSecure;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.deviceUi;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.notSignedIn;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.profileDir;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.signedIn;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelDraftStorageTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String POST_DRAFT = "channel_draft_0a1b2c3d";
	private static final String COMMENT_DRAFT =
			"channel_comment_draft_0a1b2c3d_00ff";
	private static final String TEXT = "zt synthetic draft";

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
		deviceSecure(app).edit().clear().commit();
		deviceUi(app).edit().clear().commit();
	}

	@Test
	public void theChannelScreensKeepDraftsInTheProfileSettings()
			throws Exception {
		for (String name : new String[] {"ChannelFeedActivity.java",
				"ChannelCommentsActivity.java"}) {
			String s = new String(Files.readAllBytes(Paths.get(
					"src/main/java/com/professor/zerion/android/channel/"
							+ name)), StandardCharsets.UTF_8);
			assertTrue(name, s.contains("profilePreferences()"));
			assertFalse(name + " keeps drafts in device-wide settings",
					s.contains("securePreferences()"));
			assertFalse(name, s.contains("uiPreferences()"));
			assertFalse(name, s.contains("getSharedPreferences("));
		}
	}

	@Test
	public void draftsAreSealedPerProfileAndUnreadableBeforeSignIn()
			throws Exception {
		addProfile(app, "default");
		addProfile(app, "b7c1");
		SharedPreferences a = signedIn(app, "default").preferences();
		assertTrue(a.edit().putString(POST_DRAFT, TEXT)
				.putString(COMMENT_DRAFT, TEXT).commit());

		assertNull("before sign-in",
				notSignedIn(app).preferences().getString(POST_DRAFT, null));
		assertNull("another profile",
				signedIn(app, "b7c1").preferences().getString(POST_DRAFT,
						null));

		File file = new File(profileDir(app, "default"),
				ProfileStorage.SETTINGS_FILE);
		String raw = new String(Files.readAllBytes(file.toPath()),
				StandardCharsets.ISO_8859_1);
		assertFalse(raw.contains(TEXT));
		assertFalse(raw.contains("channel_"));

		IoUtils.deleteFileOrDir(profileDir(app, "default"));
		assertFalse("deleting the profile deletes its drafts", file.exists());
	}

	@Test
	public void aDeviceWideDraftMovesIntoItsProfile() throws Exception {
		deviceSecure(app).edit().putString(POST_DRAFT, TEXT).commit();
		addProfile(app, "default");

		SharedPreferences a = signedIn(app, "default").preferences();

		assertTrue(TEXT.equals(a.getString(POST_DRAFT, null)));
		assertFalse("the device-wide copy is gone",
				deviceSecure(app).contains(POST_DRAFT));
	}
}
