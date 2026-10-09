package com.professor.zerion.android;

import android.content.Context;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.security.TestZerionEncryptedPrefs;

import org.robolectric.TestLifecycleApplication;

import java.lang.reflect.Method;
import java.security.Provider;
import java.security.Security;

public class TestZerionApplicationImpl extends ZerionApplicationImpl
		implements TestLifecycleApplication {

	private static final long STARTUP_TIMEOUT_MS = 60_000;

	@Override
	protected void attachBaseContext(Context base) {
		TestAndroidKeyStore.register();
		removeSecureRandomProvidersOfEarlierTests();
		AppModule.SecurePrefsHolder.resetForTests();
		TestZerionEncryptedPrefs.reset();
		EarlyPrefs.resetForTests();
		super.attachBaseContext(base);
	}

	private static void removeSecureRandomProvidersOfEarlierTests() {
		Provider[] installed = Security.getProviders("SecureRandom.SHA1PRNG");
		if (installed == null) return;
		for (Provider p : installed) {
			if (p.getClass().getClassLoader() != null) {
				Security.removeProvider(p.getName());
			}
		}
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
