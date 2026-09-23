package com.professor.zerion.android.login;

import android.content.Context;

import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.VaultManager;

import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A2-AND-01: the duress password must work while a sign-in lockout is
 * active, since a coercer's own wrong guesses are what start the lockout.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class StartupViewModelDuressTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final char[] DURESS = "wipe-me".toCharArray();

	private StartupViewModel model(AccountManager accountManager) {
		LifecycleManager lifecycle = mock(LifecycleManager.class);
		when(lifecycle.getLifecycleState()).thenReturn(LifecycleState.STARTING);
		BruteForceProtection bfp = new BruteForceProtection(
				RuntimeEnvironment.getApplication().getSharedPreferences(
						"duress-test", Context.MODE_PRIVATE), accountManager);
		StartupViewModel m = new StartupViewModel(
				RuntimeEnvironment.getApplication(), accountManager, lifecycle,
				mock(AndroidNotificationManager.class), bfp,
				mock(EventBus.class), mock(VaultManager.class), Runnable::run);
		m.setDuressCheck(p -> Arrays.equals(p, DURESS));
		return m;
	}

	@Test
	public void theDuressPasswordWipesDuringALockout() throws Exception {
		AccountManager am = mock(AccountManager.class);
		when(am.signInLockoutRemainingMs()).thenReturn(300_000L);
		StartupViewModel m = model(am);
		m.validatePassword(DURESS.clone());
		verify(am).deleteAccount();
		verify(am, never()).signIn(any());
	}

	@Test
	public void anOrdinaryPasswordDuringALockoutOnlyReportsTheLockout()
			throws Exception {
		AccountManager am = mock(AccountManager.class);
		when(am.signInLockoutRemainingMs()).thenReturn(300_000L);
		StartupViewModel m = model(am);
		m.validatePassword("guess".toCharArray());
		verify(am, never()).deleteAccount();
		verify(am, never()).signIn(any());
	}
}
