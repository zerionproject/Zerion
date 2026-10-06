package com.professor.zerion.android.account;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.ClearableClipboardShadow;
import com.professor.zerion.android.util.SecureClipboard;
import com.professor.zerion.android.vault.DirectorySyncShadow;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.settings.SettingsManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class LockManagerClipboardTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Application app = ApplicationProvider.getApplicationContext();

	private ClipboardManager clipboard() {
		return (ClipboardManager) app.getSystemService(
				Context.CLIPBOARD_SERVICE);
	}

	private LockManagerImpl lockManager() {
		return new LockManagerImpl(app, mock(SettingsManager.class),
				mock(AndroidNotificationManager.class), Runnable::run,
				Runnable::run, () -> mock(com.professor.zerion.android.vault.VaultManager
						.class));
	}

	@Test
	public void ourClipIsClearedWhenTheAppLocks() {
		SecureClipboard.copy(app, "address", "a-copied-value");
		lockManager().setLocked(true);
		assertFalse("the lock cleared the value the app copied",
				clipboard().hasPrimaryClip());
	}

	@Test
	public void aForeignClipSurvivesTheLock() {
		SecureClipboard.copy(app, "address", "a-copied-value");
		clipboard().setPrimaryClip(ClipData.newPlainText("x", "foreign"));
		lockManager().setLocked(true);
		assertEquals("foreign",
				clipboard().getPrimaryClip().getItemAt(0).getText()
						.toString());
	}
}
