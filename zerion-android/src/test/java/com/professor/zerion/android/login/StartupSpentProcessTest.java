package com.professor.zerion.android.login;

import com.professor.zerion.android.ZerionService;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.splash.SplashExitPendingTest;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class StartupSpentProcessTest {

	static {
		TestAndroidKeyStore.register();
	}

	@After
	public void tearDown() throws Exception {
		ZerionService.cancelPendingExit();
		SplashExitPendingTest.lifecycleIs(LifecycleState.CREATED);
	}

	@Test
	public void theSignInScreenDoesNotReviveAStoppedSession()
			throws Exception {
		SplashExitPendingTest.lifecycleIs(LifecycleState.STOPPED);
		StartupActivity a = Robolectric.buildActivity(StartupActivity.class)
				.setup().get();
		assertTrue("sign in was offered in a stopped session",
				a.isFinishing());
		Field f = ZerionService.class.getDeclaredField("exitInProgress");
		f.setAccessible(true);
		assertTrue("the stopped session's process is not ended",
				((AtomicBoolean) f.get(null)).get());
	}
}
