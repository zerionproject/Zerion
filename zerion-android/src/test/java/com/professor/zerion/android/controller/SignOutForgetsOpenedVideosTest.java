package com.professor.zerion.android.controller;

import android.app.Activity;
import android.app.Application;

import com.professor.zerion.android.ZerionService.ZerionServiceConnection;
import com.professor.zerion.android.api.DozeWatchdog;
import com.professor.zerion.android.conversation.OpenedVideos;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.ClearableClipboardShadow;
import com.professor.zerion.android.vault.DirectorySyncShadow;
import com.professor.zerion.android.vault.VaultManager;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.settings.SettingsManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class SignOutForgetsOpenedVideosTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Application app = ApplicationProvider.getApplicationContext();

	@After
	public void tearDown() {
		OpenedVideos.clear();
	}

	@Test
	public void signingOutForgetsWhichVideosWereOpened() {
		Activity activity = mock(Activity.class);
		when(activity.getApplicationContext()).thenReturn(app);
		ZerionControllerImpl controller = new ZerionControllerImpl(
				mock(ZerionServiceConnection.class),
				mock(AccountManager.class), mock(LifecycleManager.class),
				Runnable::run, mock(SettingsManager.class),
				mock(DozeWatchdog.class), mock(AndroidWakeLockManager.class),
				mock(VaultManager.class), activity);
		OpenedVideos.add("group video");

		controller.signOut(result -> {
		}, false);

		assertFalse("a watched video would be parsed again after sign-in",
				OpenedVideos.contains("group video"));
	}
}
