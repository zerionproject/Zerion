package com.professor.zerion.android.splash;

import android.os.SystemClock;

import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.ZerionService;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager.LifecycleState;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SplashExitPendingTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static Field field(String name) throws Exception {
		Field f = ZerionService.class.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}

	@After
	public void tearDown() throws Exception {
		ZerionService.cancelPendingExit();
		lifecycleIs(LifecycleState.CREATED);
	}

	@SuppressWarnings("unchecked")
	public static void lifecycleIs(LifecycleState s) throws Exception {
		LifecycleManager lm = ((ZerionApplication) RuntimeEnvironment
				.getApplication()).getApplicationComponent().lifecycleManager();
		Field f = lm.getClass().getDeclaredField("state");
		f.setAccessible(true);
		((AtomicReference<LifecycleState>) f.get(lm)).set(s);
	}

	private static boolean exitArmed() throws Exception {
		return ((AtomicBoolean) field("exitInProgress").get(null)).get();
	}

	private static void exitStartedLongAgo() throws Exception {
		((AtomicBoolean) field("exitInProgress").get(null)).set(true);
		((AtomicLong) field("exitStartedAt").get(null)).set(
				SystemClock.elapsedRealtime() - 60_000);
	}

	private static void exitUnderWay() throws Exception {
		((AtomicBoolean) field("exitInProgress").get(null)).set(true);
		try {
			((AtomicLong) field("exitStartedAt").get(null))
					.set(SystemClock.elapsedRealtime());
		} catch (NoSuchFieldException ignored) {
		}
	}

	@Test
	public void aReopenAfterTheGraceDoesNotReviveAStoppedSession()
			throws Exception {
		exitStartedLongAgo();
		lifecycleIs(LifecycleState.STOPPED);
		SplashScreenActivity a = Robolectric.buildActivity(
				SplashScreenActivity.class).setup().get();
		assertTrue("a stopped session was opened again", a.isFinishing());
		assertTrue("the exit of the stopped session was cancelled",
				exitArmed());
	}

	@Test
	public void aSessionStillStoppingIsNotOpenedAgain() throws Exception {
		lifecycleIs(LifecycleState.STOPPING);
		SplashScreenActivity a = Robolectric.buildActivity(
				SplashScreenActivity.class).setup().get();
		assertTrue(a.isFinishing());
		assertTrue("nothing ends the process of the stopping session",
				exitArmed());
	}

	@Test
	public void aFreshProcessClearsAnOldExit() throws Exception {
		exitStartedLongAgo();
		Robolectric.buildActivity(SplashScreenActivity.class).setup();
		assertFalse("an old exit stayed armed in a fresh process",
				exitArmed());
	}

	@Test
	public void anotherAppCannotCancelAPendingExit() throws Exception {
		exitUnderWay();
		SplashScreenActivity a = Robolectric.buildActivity(
				SplashScreenActivity.class).setup().get();
		assertTrue("the pending exit was cancelled",
				((AtomicBoolean) field("exitInProgress").get(null)).get());
		assertTrue("the entry stays open", a.isFinishing());
	}
}
