package com.professor.zerion.android;

import android.content.Context;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.security.TestZerionEncryptedPrefs;

import org.robolectric.TestLifecycleApplication;

import java.lang.reflect.Method;

public class TestZerionApplicationImpl extends ZerionApplicationImpl
		implements TestLifecycleApplication {

	private static final long STARTUP_TIMEOUT_MS = 60_000;

	static {
		TestAndroidKeyStore.register();
	}

	@Override
	protected void attachBaseContext(Context base) {
		AppModule.SecurePrefsHolder.resetForTests();
		TestZerionEncryptedPrefs.reset();
		EarlyPrefs.resetForTests();
		super.attachBaseContext(base);
	}

	@Override
	public void beforeTest(Method method) {
		try {
			if (!awaitEagerSingletons(STARTUP_TIMEOUT_MS)) {
				throw new AssertionError(
						"the application did not finish starting up");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}

	@Override
	public void prepareTest(Object test) {
	}

	@Override
	public void afterTest(Method method) {
	}
}
