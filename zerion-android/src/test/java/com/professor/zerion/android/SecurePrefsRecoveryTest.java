package com.professor.zerion.android;

import android.app.Application;
import android.content.SharedPreferences;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.security.ZerionEncryptedPrefs;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SecurePrefsRecoveryTest {

	static {
		TestAndroidKeyStore.register();
	}

	@After
	public void tearDown() throws Exception {
		forgetOpenedPrefs();
		AppModule.SecurePrefsHolder.initialize(app());
	}

	private static void forgetOpenedPrefs() throws Exception {
		AppModule.SecurePrefsHolder.resetForTests();
		Method reset = ZerionEncryptedPrefs.class
				.getDeclaredMethod("resetForTests");
		reset.setAccessible(true);
		reset.invoke(null);
	}

	private static void setStatic(Class<?> c, String name, Object value)
			throws Exception {
		Field f = c.getDeclaredField(name);
		f.setAccessible(true);
		f.set(null, value);
	}

	private static void leaveHolderFailed() throws Exception {
		Class<?> holder = AppModule.SecurePrefsHolder.class;
		Class<?> failClosed = Class.forName(
				"com.professor.zerion.android.AppModule$FailClosedPrefs");
		Constructor<?> ctor = failClosed.getDeclaredConstructor();
		ctor.setAccessible(true);
		setStatic(holder, "securePrefs", ctor.newInstance());
		setStatic(holder, "uiPrefs", ctor.newInstance());
		setStatic(holder, "initFailed", true);
	}

	private static void leaveCreationFailed() throws Exception {
		setStatic(ZerionEncryptedPrefs.class, "storageFailed", true);
	}

	@Test
	public void aFailedFirstStartDoesNotPinTheProcessToFailure()
			throws Exception {
		forgetOpenedPrefs();
		leaveHolderFailed();
		leaveCreationFailed();
		assertTrue("the failed start is reported",
				AppModule.isSecureStorageFailed());

		AppModule.SecurePrefsHolder.initialize(app());

		assertFalse("the next start opens the settings",
				AppModule.isSecureStorageFailed());
		SharedPreferences ui = AppModule.getUiPrefs();
		assertTrue(ui instanceof ZerionEncryptedPrefs);
		ui.edit().putInt("recovered", 7).commit();
		assertEquals(7, AppModule.getUiPrefs().getInt("recovered", 0));
	}

	@Test
	public void aScreenAskingBeforeAnyStartOpensTheSettingsFirst()
			throws Exception {
		forgetOpenedPrefs();
		leaveCreationFailed();
		assertTrue("a creation failure with nothing open is reported",
				AppModule.isSecureStorageFailed());

		assertFalse("the check tries once before judging",
				AppModule.isSecureStorageFailed(app()));
		assertFalse(AppModule.isSecureStorageFailed());
	}

	@Test
	public void aSuccessfulStartIsNotReopened() throws Exception {
		forgetOpenedPrefs();
		AppModule.SecurePrefsHolder.initialize(app());
		Object first = AppModule.getUiPrefs();

		AppModule.SecurePrefsHolder.initialize(app());
		assertFalse(AppModule.isSecureStorageFailed(app()));

		assertTrue(first == AppModule.getUiPrefs());
	}

	private static Application app() {
		return ApplicationProvider.getApplicationContext();
	}
}
