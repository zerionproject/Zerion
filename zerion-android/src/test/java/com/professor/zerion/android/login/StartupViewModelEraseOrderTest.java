package com.professor.zerion.android.login;

import android.content.Context;
import android.content.SharedPreferences;

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
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class StartupViewModelEraseOrderTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final char[] DURESS = "wipe-me".toCharArray();

	private static final class SimulatedProcessDeath extends Error {
		SimulatedProcessDeath() {
			super("injected process death");
		}
	}

	private final List<String> steps = new ArrayList<>();
	private final AccountManager am = mock(AccountManager.class);
	private final VaultManager vault = mock(VaultManager.class);

	private SharedPreferences securePrefs() {
		return RuntimeEnvironment.getApplication().getSharedPreferences(
				"secure_prefs_v2", Context.MODE_PRIVATE);
	}

	private String eraseSetting() {
		return securePrefs().getBoolean("bf_wipe", false) ? "on" : "off";
	}

	private StartupViewModel model(boolean diesAfterTheFirstStep)
			throws Exception {
		doAnswer(i -> step("key made unusable, erase setting "
				+ eraseSetting(), diesAfterTheFirstStep))
				.when(am).shredDatabaseKey();
		doAnswer(i -> step("vault wiped, erase setting " + eraseSetting(),
				diesAfterTheFirstStep)).when(vault).wipeVault();
		doAnswer(i -> step("account deleted, erase setting "
				+ eraseSetting(), diesAfterTheFirstStep))
				.when(am).deleteAccount();
		BruteForceProtection bfp = new BruteForceProtection(securePrefs(), am);
		bfp.setWipeOnRepeatedFailures(true);
		LifecycleManager lifecycle = mock(LifecycleManager.class);
		when(lifecycle.getLifecycleState()).thenReturn(LifecycleState.STARTING);
		StartupViewModel m = new StartupViewModel(
				RuntimeEnvironment.getApplication(), am, lifecycle,
				mock(AndroidNotificationManager.class), bfp,
				mock(EventBus.class), vault, Runnable::run);
		m.setDuressCheck(p -> Arrays.equals(p, DURESS));
		return m;
	}

	private Object step(String step, boolean dieAfterFirst) {
		steps.add(step);
		if (dieAfterFirst && steps.size() == 1) {
			throw new SimulatedProcessDeath();
		}
		return null;
	}

	@Test
	public void anEraseAfterRepeatedFailuresMakesTheKeyUnusableFirst()
			throws Exception {
		AtomicInteger counted = new AtomicInteger();
		when(am.failedSignInAttempts()).thenAnswer(i -> counted.get());
		when(am.isEraseRequested()).thenAnswer(i -> counted.get()
				>= BruteForceProtection.ATTEMPTS_BEFORE_WIPE);
		doAnswer(i -> {
			counted.incrementAndGet();
			throw new DecryptionException(DecryptionResult.INVALID_PASSWORD);
		}).when(am).signIn(any());
		StartupViewModel m = model(false);
		for (int i = 0; i < BruteForceProtection.ATTEMPTS_BEFORE_WIPE; i++) {
			m.validatePassword("guess".toCharArray());
		}
		assertEquals("[key made unusable, erase setting on, vault wiped,"
				+ " erase setting on, account deleted, erase setting off]",
				steps.toString());
	}

	@Test
	public void aDuressEraseMakesTheKeyUnusableFirst() throws Exception {
		when(am.signInLockoutRemainingMs()).thenReturn(300_000L);
		StartupViewModel m = model(false);
		m.validatePassword(DURESS.clone());
		assertEquals("[key made unusable, erase setting on, vault wiped,"
				+ " erase setting on, account deleted, erase setting off]",
				steps.toString());
	}

	@Test
	public void aProcessKilledAfterTheFirstStepLeavesNoUsableKey()
			throws Exception {
		when(am.signInLockoutRemainingMs()).thenReturn(300_000L);
		StartupViewModel m = model(true);
		String died = "no";
		try {
			m.validatePassword(DURESS.clone());
		} catch (SimulatedProcessDeath expected) {
			died = "yes";
		}
		assertEquals("died=yes steps=[key made unusable, erase setting on]"
						+ " erase setting now on",
				"died=" + died + " steps=" + steps + " erase setting now "
						+ eraseSetting());
	}
}
