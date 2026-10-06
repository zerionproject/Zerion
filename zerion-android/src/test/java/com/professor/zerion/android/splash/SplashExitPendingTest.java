package com.professor.zerion.android.splash;

import android.os.SystemClock;

import com.professor.zerion.android.ZerionService;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import androidx.test.ext.junit.runners.AndroidJUnit4;

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
	public void tearDown() {
		ZerionService.cancelPendingExit();
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
	public void anotherAppCannotCancelAPendingExit() throws Exception {
		exitUnderWay();
		SplashScreenActivity a = Robolectric.buildActivity(
				SplashScreenActivity.class).setup().get();
		assertTrue("the pending exit was cancelled",
				((AtomicBoolean) field("exitInProgress").get(null)).get());
		assertTrue("the entry stays open", a.isFinishing());
	}
}
