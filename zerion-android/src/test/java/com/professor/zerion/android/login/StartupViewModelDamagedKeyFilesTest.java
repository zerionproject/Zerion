package com.professor.zerion.android.login;

import android.content.Context;
import android.os.Looper;

import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.VaultManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class StartupViewModelDamagedKeyFilesTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static Object count(AtomicInteger counter) {
		counter.incrementAndGet();
		return null;
	}

	@Test
	public void repeatedDamagedKeyFilesNeverErase() throws Exception {
		AtomicInteger counted = new AtomicInteger();
		AtomicInteger deleted = new AtomicInteger();
		AtomicInteger shredded = new AtomicInteger();
		AtomicInteger vaultWiped = new AtomicInteger();
		AccountManager am = mock(AccountManager.class);
		when(am.failedSignInAttempts()).thenAnswer(i -> counted.get());
		doAnswer(i -> {
			counted.incrementAndGet();
			throw new DecryptionException(DecryptionResult.KEY_FILES_DAMAGED);
		}).when(am).signIn(any());
		doAnswer(i -> count(deleted)).when(am).deleteAccount();
		doAnswer(i -> count(shredded)).when(am).shredDatabaseKey();
		VaultManager vault = mock(VaultManager.class);
		doAnswer(i -> count(vaultWiped)).when(vault).wipeVault();

		BruteForceProtection bfp = new BruteForceProtection(
				RuntimeEnvironment.getApplication().getSharedPreferences(
						"damaged-test", Context.MODE_PRIVATE), am);
		bfp.setWipeOnRepeatedFailures(true);
		LifecycleManager lifecycle = mock(LifecycleManager.class);
		when(lifecycle.getLifecycleState()).thenReturn(LifecycleState.STARTING);
		StartupViewModel m = new StartupViewModel(
				RuntimeEnvironment.getApplication(), am, lifecycle,
				mock(AndroidNotificationManager.class), bfp,
				mock(EventBus.class), vault, Runnable::run);
		m.setDuressCheck(p -> false);
		List<DecryptionResult> shown = new ArrayList<>();
		List<Boolean> wipes = new ArrayList<>();
		m.getPasswordValidated().observeEventForever(shown::add);
		m.getTriggerWipe().observeEventForever(wipes::add);

		int attempts = 2 * BruteForceProtection.ATTEMPTS_BEFORE_WIPE;
		for (int i = 0; i < attempts; i++) {
			m.validatePassword("the right password".toCharArray());
			shadowOf(Looper.getMainLooper()).idle();
		}

		int damagedShown = 0;
		for (DecryptionResult r : shown) {
			if (r == DecryptionResult.KEY_FILES_DAMAGED) damagedShown++;
		}
		assertEquals("damaged shown " + attempts + " times, counted "
						+ attempts + ", erase triggered 0, key shredded 0,"
						+ " vault wiped 0, account deleted 0, erase policy on",
				"damaged shown " + damagedShown + " times, counted "
						+ counted.get() + ", erase triggered " + wipes.size()
						+ ", key shredded " + shredded.get()
						+ ", vault wiped " + vaultWiped.get()
						+ ", account deleted " + deleted.get()
						+ ", erase policy "
						+ (bfp.isWipeOnRepeatedFailures() ? "on" : "off"));
	}
}
