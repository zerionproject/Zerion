package com.professor.zerion.android.controller;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;

import com.professor.zerion.android.ZerionService.ZerionServiceConnection;
import com.professor.zerion.android.api.DozeWatchdog;
import com.professor.zerion.android.message.PendingSends;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.settings.SettingsActivity;
import com.professor.zerion.android.util.ClearableClipboardShadow;
import com.professor.zerion.android.vault.DirectorySyncShadow;
import com.professor.zerion.android.vault.VaultManager;

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
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class SignOutClearsUnsentMessagesTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String SCOPE =
			PendingSends.groupScope(new byte[] {4, 2});

	private final Application app = ApplicationProvider.getApplicationContext();

	private static void keepSomething() {
		PendingSends.get().addNotSent(SCOPE, "secret", null, 6, true,
				() -> PendingSends.Result.failed(null));
		assertEquals(1, PendingSends.get().entries(SCOPE).size());
	}

	@Test
	public void signingOutRemovesUnsentRows() {
		keepSomething();
		Activity activity = mock(Activity.class);
		when(activity.getApplicationContext()).thenReturn(app);
		new ZerionControllerImpl(mock(ZerionServiceConnection.class),
				mock(AccountManager.class), mock(LifecycleManager.class),
				Runnable::run, mock(SettingsManager.class),
				mock(DozeWatchdog.class), mock(AndroidWakeLockManager.class),
				mock(VaultManager.class), activity).signOut(result -> {
		}, false);
		assertTrue(PendingSends.get().entries(SCOPE).isEmpty());
	}

	private static void leaveACountFromALock() {
		keepSomething();
		PendingSends.get().clear();
		keepSomething();
	}

	private static void assertNothingLeftToTell() {
		assertEquals("no lock count carries over", 0,
				PendingSends.get().takeRemovedOnLock());
		assertTrue(PendingSends.get().takeNotices(SCOPE).isEmpty());
	}

	@Test
	public void signingOutLeavesNothingToTellAfterwards() {
		leaveACountFromALock();
		Activity activity = mock(Activity.class);
		when(activity.getApplicationContext()).thenReturn(app);
		new ZerionControllerImpl(mock(ZerionServiceConnection.class),
				mock(AccountManager.class), mock(LifecycleManager.class),
				Runnable::run, mock(SettingsManager.class),
				mock(DozeWatchdog.class), mock(AndroidWakeLockManager.class),
				mock(VaultManager.class), activity).signOut(result -> {
		}, false);
		assertTrue(PendingSends.get().entries(SCOPE).isEmpty());
		assertNothingLeftToTell();
	}

	@Test
	public void signingOutFromSettingsLeavesNothingToTellAfterwards() {
		SettingsActivity activity = Robolectric
				.buildActivity(SettingsActivity.class).setup().get();
		shadowOf(Looper.getMainLooper()).idle();
		leaveACountFromALock();
		activity.requestProfileSignOut();
		shadowOf(Looper.getMainLooper()).idle();
		assertTrue(PendingSends.get().entries(SCOPE).isEmpty());
		assertNothingLeftToTell();
	}

	@Test
	public void signingOutOfAProfileRemovesUnsentRows() {
		SettingsActivity activity = Robolectric
				.buildActivity(SettingsActivity.class).setup().get();
		shadowOf(Looper.getMainLooper()).idle();
		keepSomething();
		activity.requestProfileSignOut();
		shadowOf(Looper.getMainLooper()).idle();
		assertTrue(PendingSends.get().entries(SCOPE).isEmpty());
	}
}
