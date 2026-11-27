package com.professor.zerion.android.account;

import android.app.Application;

import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.conversation.OpenedVideos;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.ClearableClipboardShadow;
import com.professor.zerion.android.vault.DirectorySyncShadow;
import com.professor.zerion.android.vault.VaultManager;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.settings.SettingsManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class LockForgetsOpenedVideosTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Application app = ApplicationProvider.getApplicationContext();

	@After
	public void tearDown() {
		OpenedVideos.clear();
	}

	private LockManagerImpl lockManager() {
		return new LockManagerImpl(app, mock(SettingsManager.class),
				mock(AndroidNotificationManager.class), Runnable::run,
				Runnable::run, () -> mock(VaultManager.class));
	}

	@Test
	public void lockingTheAppForgetsWhichVideosWereOpened() {
		LockManagerImpl lock = lockManager();
		OpenedVideos.add("chat video");
		lock.setLocked(false);
		assertTrue(OpenedVideos.contains("chat video"));

		lock.setLocked(true);
		assertFalse("a watched video would be parsed again after unlock",
				OpenedVideos.contains("chat video"));
	}
}
