package com.professor.zerion.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.security.ZerionEncryptedPrefs;
import com.professor.zerion.android.vault.PreferencesMigration;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowApplication;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class NonMainProcessStartTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Application app = RuntimeEnvironment.getApplication();

	private void inStartupFailureProcess() {
		ShadowApplication.setProcessName(
				app.getPackageName() + ":zerion_startup_failure");
		assertFalse(ProcessRole.isMain(app));
	}

	private void inMainProcess() {
		ShadowApplication.setProcessName(app.getPackageName());
		assertTrue(ProcessRole.isMain(app));
	}

	@After
	public void backInTheMainProcess() {
		ShadowApplication.setProcessName(app.getPackageName());
		delete(new File(app.getFilesDir(), "profiles/a1"));
		delete(new File(app.getFilesDir(), "profiles/b7c1"));
		delete(new File(app.getFilesDir(), "profiles/c3"));
	}

	private File write(String path) throws IOException {
		File f = new File(app.getFilesDir(), path);
		assertTrue(f.getParentFile().isDirectory()
				|| f.getParentFile().mkdirs());
		Files.write(f.toPath(), new byte[] {1, 2, 3});
		return f;
	}

	@Test
	public void profileDataIsNeitherMovedNorWipedOutsideTheMainProcess()
			throws Exception {
		File key = write("profiles/a1/key/db.key");
		File withoutKey = write("profiles/b7c1/vault/vault.header");
		write("profiles/c3/.deleted");
		File marked = write("profiles/c3/db/db.sqlite");

		inStartupFailureProcess();
		new AppModule(app).provideProfileManager(app);

		assertTrue(key.exists());
		assertTrue("a profile without a key is kept", withoutKey.exists());
		assertTrue("a profile marked for deletion is kept", marked.exists());

		inMainProcess();
		new AppModule(app).provideProfileManager(app);

		assertTrue(key.exists());
		assertFalse(withoutKey.exists());
		assertFalse(marked.exists());
	}

	@Test
	public void settingsAreNotMigratedOutsideTheMainProcess() {
		SharedPreferences old = app.getSharedPreferences("vault_settings",
				Context.MODE_PRIVATE);
		assertTrue(old.edit().putInt("autolock_timeout", 5).commit());
		SharedPreferences fresh = app.getSharedPreferences(
				"probe_secure_prefs", Context.MODE_PRIVATE);
		assertTrue(fresh.edit().clear().commit());
		AppModule.EagerSingletons startup = new AppModule.EagerSingletons();
		startup.application = app;
		startup.preferencesMigration = new PreferencesMigration(app, fresh,
				fresh);

		inStartupFailureProcess();
		startup.init();

		assertEquals(5, old.getInt("autolock_timeout", -1));
		assertFalse(fresh.contains("autolock_timeout"));

		inMainProcess();
		startup.init();

		assertEquals(5, fresh.getInt("autolock_timeout", -1));
		assertFalse(old.contains("autolock_timeout"));
	}

	@Test
	public void oldEncryptedSettingsAreNotDeletedOutsideTheMainProcess() {
		SharedPreferences legacy = app.getSharedPreferences(
				"probe_legacy_prefs", Context.MODE_PRIVATE);
		assertTrue(legacy.edit().putString("k", "v").commit());

		inStartupFailureProcess();
		ZerionEncryptedPrefs.create(app, "probe_legacy_prefs");

		assertEquals("v", legacy.getString("k", null));

		SharedPreferences other = app.getSharedPreferences(
				"probe_legacy_prefs_main", Context.MODE_PRIVATE);
		assertTrue(other.edit().putString("k", "v").commit());
		inMainProcess();
		ZerionEncryptedPrefs.create(app, "probe_legacy_prefs_main");

		assertFalse(app.getSharedPreferences("probe_legacy_prefs_main",
				Context.MODE_PRIVATE).contains("k"));
	}

	private static void delete(File f) {
		File[] children = f.listFiles();
		if (children != null) {
			for (File c : children) delete(c);
		}
		f.delete();
	}
}
