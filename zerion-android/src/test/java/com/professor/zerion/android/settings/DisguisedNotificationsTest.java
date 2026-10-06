package com.professor.zerion.android.settings;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Looper;

import com.professor.zerion.R;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class DisguisedNotificationsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String LINK_ENTRY =
			"com.professor.zerion.android.navdrawer.ExternalLinkActivity";

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@After
	public void tearDown() {
		AppIconManager.setAppIcon(ctx, AppIconManager.ICON_DEFAULT);
	}

	private AndroidNotificationManager notifications() {
		return ((ZerionApplication) ctx).getApplicationComponent()
				.androidNotificationManager();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static String title(Notification n) {
		return String.valueOf(n.extras.getCharSequence(
				Notification.EXTRA_TITLE));
	}

	private static String text(Notification n) {
		return String.valueOf(n.extras.getCharSequence(
				Notification.EXTRA_TEXT));
	}

	@Test
	public void aDisguisedAppsNotificationDoesNotNameIt() {
		AppIconManager.setAppIcon(ctx, AppIconManager.ICON_CALCULATOR);
		idle();
		Notification n = notifications().getForegroundNotification();
		assertFalse(title(n), title(n).contains("Zerion"));
		assertFalse(text(n), text(n).contains("Zerion"));
		assertFalse(text(n), text(n).contains("Tor"));
		assertEquals("Calculator", title(n));
		assertNotEquals(R.drawable.logo, n.getSmallIcon().getResId());
		Intent open = shadowOf(n.contentIntent).getSavedIntent();
		assertEquals("com.professor.zerion.launcher.Calculator",
				open.getComponent().getClassName());
	}

	@Test
	public void withoutADisguiseTheNotificationIsUnchanged() {
		AppIconManager.setAppIcon(ctx, AppIconManager.ICON_DEFAULT);
		idle();
		Notification n = notifications().getForegroundNotification();
		assertEquals(ctx.getString(R.string.ongoing_notification_title),
				title(n));
		Intent open = shadowOf(n.contentIntent).getSavedIntent();
		assertEquals(
				"com.professor.zerion.android.splash.SplashScreenActivity",
				open.getComponent().getClassName());
	}

	@Test
	public void otherAppsAreNotOfferedADisguisedApp() {
		PackageManager pm = ctx.getPackageManager();
		ComponentName link = new ComponentName(ctx, LINK_ENTRY);
		AppIconManager.setAppIcon(ctx, AppIconManager.ICON_NOTES);
		assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
				pm.getComponentEnabledSetting(link));
		AppIconManager.setAppIcon(ctx, AppIconManager.ICON_DEFAULT);
		assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
				pm.getComponentEnabledSetting(link));
	}

	@Test
	public void noNotificationIsHeadedWithTheAppNameDirectly()
			throws Exception {
		String s = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/"
						+ "AndroidNotificationManagerImpl.java")),
				StandardCharsets.UTF_8);
		assertFalse(s.contains("R.string.app_name"));
		assertFalse("a notification opens the default entry by name",
				s.contains("SplashScreenActivity.class"));
	}
}
