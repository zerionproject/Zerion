package com.professor.zerion.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.security.Provider;
import java.security.SecureRandomSpi;
import java.security.Security;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class TestApplicationIsolationTest {

	private static final String PROBE = "IsolationProbePRNG";

	public static final class ProbePrng extends Provider {

		public ProbePrng() {
			super(PROBE, 1.0, "left behind by an earlier test");
			put("SecureRandom.SHA1PRNG", ProbeSpi.class.getName());
		}
	}

	public static final class ProbeSpi extends SecureRandomSpi {

		@Override
		protected void engineSetSeed(byte[] seed) {
		}

		@Override
		protected void engineNextBytes(byte[] bytes) {
			throw new UnsupportedOperationException();
		}

		@Override
		protected byte[] engineGenerateSeed(int numBytes) {
			throw new UnsupportedOperationException();
		}
	}

	private final Application app = RuntimeEnvironment.getApplication();

	@Test
	public void everyTestRunsInTheTestApplication() {
		assertTrue(app.getClass().getName(),
				app instanceof TestZerionApplicationImpl);
	}

	@Test
	public void theStartupHasFinishedBeforeTheTestBegins() throws Exception {
		assertTrue(((ZerionApplicationImpl) app).awaitEagerSingletons(1));
	}

	@Test
	public void theSettingsOfOneTestAreStoredInItsOwnApplication() {
		assertSettingsStoredInThisApplication();
	}

	@Test
	public void theSettingsOfTheNextTestAreStoredInItsOwnApplication() {
		assertSettingsStoredInThisApplication();
	}

	@Test
	public void aSecureRandomProviderDoesNotOutliveItsTest() {
		assertOnlyThisTestsProvidersAndLeaveOneBehind();
	}

	@Test
	public void aSecureRandomProviderDoesNotOutliveTheNextTest() {
		assertOnlyThisTestsProvidersAndLeaveOneBehind();
	}

	@Test
	public void theKeyStoreIsTheOneOfThisTestsSandbox() {
		assertTrue(Security.getProvider("AndroidKeyStore")
				instanceof TestAndroidKeyStore.KeyStoreProvider);
	}

	private static void assertOnlyThisTestsProvidersAndLeaveOneBehind() {
		assertNull(Security.getProvider(PROBE));
		Provider[] prngs = Security.getProviders("SecureRandom.SHA1PRNG");
		if (prngs != null) {
			for (Provider p : prngs) {
				ClassLoader loader = p.getClass().getClassLoader();
				assertTrue(p.getName(), loader == null || loader
						== TestApplicationIsolationTest.class.getClassLoader());
			}
		}
		Security.addProvider(new ProbePrng());
	}

	private void assertSettingsStoredInThisApplication() {
		SharedPreferences ui = app.getSharedPreferences("ui_prefs_v2",
				Context.MODE_PRIVATE);
		int before = ui.getAll().size();
		assertTrue(AppModule.getUiPrefs().edit()
				.putInt("isolation_probe", 1).commit());
		assertEquals(before + 1, ui.getAll().size());
		assertFalse("the theme chosen at start is in another application",
				app.getSharedPreferences("early_ui_prefs_v2",
						Context.MODE_PRIVATE).getAll().isEmpty());
	}
}
