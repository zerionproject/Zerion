package com.professor.zerion.android.login;

import android.app.Application;
import android.content.Context;
import android.os.Looper;

import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.VaultManager;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState;
import org.zerionproject.core.util.IoUtils;

import java.io.File;
import java.util.concurrent.atomic.AtomicReference;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.AppModule.getAndroidComponent;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class UsbPanicWipeResumesAtStartTest {

	static {
		TestAndroidKeyStore.register();
	}

	@After
	public void tearDown() {
		Context app = RuntimeEnvironment.getApplication();
		IoUtils.deleteFileOrDir(new File(app.getFilesDir(), "profiles"));
	}

	@Test
	public void aWipeCutShortAfterItsFirstStepIsCompletedAtTheNextStart() {
		Application app = RuntimeEnvironment.getApplication();
		AccountManager accounts = getAndroidComponent(app).accountManager();
		assertTrue(accounts.createAccount("Dana",
				"a usb panic password".toCharArray()));
		assertTrue(accounts.accountExists());

		accounts.shredDatabaseKey();

		assertTrue("the wipe left a durable mark before anything else",
				accounts.isEraseRequested());
		assertFalse("the account can no longer be opened",
				accounts.accountExists());

		LifecycleManager lifecycle = mock(LifecycleManager.class);
		when(lifecycle.getLifecycleState()).thenReturn(LifecycleState.CREATED);
		StartupViewModel next = new StartupViewModel(app, accounts, lifecycle,
				mock(AndroidNotificationManager.class),
				new BruteForceProtection(app.getSharedPreferences(
						"usb-panic-resume", Context.MODE_PRIVATE), accounts),
				mock(EventBus.class), mock(VaultManager.class), Runnable::run);
		AtomicReference<StartupViewModel.StartupDecision> decision =
				new AtomicReference<>();
		next.decideStartAsync(decision::set);
		shadowOf(Looper.getMainLooper()).idle();

		assertEquals(StartupViewModel.StartupDecision.ERASED, decision.get());
		assertFalse(accounts.isEraseRequested());
		assertFalse(accounts.accountExists());
	}
}
