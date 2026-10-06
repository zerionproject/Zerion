package com.professor.zerion.android.controller;

import android.app.Activity;
import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;

import com.professor.zerion.android.ZerionService.ZerionServiceConnection;
import com.professor.zerion.android.api.DozeWatchdog;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.settings.SettingsActivity;
import com.professor.zerion.android.util.ClearableClipboardShadow;
import com.professor.zerion.android.util.SecureClipboard;
import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.DirectorySyncShadow;

import org.briarproject.android.dontkillmelib.wakelock.AndroidWakeLockManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.settings.SettingsManager;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class SignOutClearsClipboardTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Application app = ApplicationProvider.getApplicationContext();

	private static ClipboardManager clipboard(Context c) {
		return (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
	}

	private ZerionControllerImpl controller() {
		Activity activity = mock(Activity.class);
		when(activity.getApplicationContext()).thenReturn(app);
		return new ZerionControllerImpl(mock(ZerionServiceConnection.class),
				mock(AccountManager.class), mock(LifecycleManager.class),
				Runnable::run, mock(SettingsManager.class),
				mock(DozeWatchdog.class), mock(AndroidWakeLockManager.class),
				mock(VaultManager.class), activity);
	}

	@Test
	public void signingOutClearsOurClip() {
		SecureClipboard.copy(app, "address", "a-copied-value");
		controller().signOut(result -> {
		}, false);
		assertFalse("sign-out left the app's copy on the clipboard",
				clipboard(app).hasPrimaryClip());
	}

	@Test
	public void signingOutLeavesAForeignClip() {
		SecureClipboard.copy(app, "address", "a-copied-value");
		clipboard(app).setPrimaryClip(ClipData.newPlainText("x", "foreign"));
		controller().signOut(result -> {
		}, false);
		assertEquals("foreign", clipboard(app).getPrimaryClip().getItemAt(0)
				.getText().toString());
	}

	@Test
	public void aSignOutFromTheUiWithoutASessionClearsOurClip() {
		SettingsActivity activity = Robolectric
				.buildActivity(SettingsActivity.class).setup().get();
		shadowOf(Looper.getMainLooper()).idle();
		SecureClipboard.copy(activity, "address", "a-copied-value");
		activity.requestProfileSignOut();
		shadowOf(Looper.getMainLooper()).idle();
		assertFalse("sign-out left the app's copy on the clipboard",
				clipboard(activity).hasPrimaryClip());
	}
}
