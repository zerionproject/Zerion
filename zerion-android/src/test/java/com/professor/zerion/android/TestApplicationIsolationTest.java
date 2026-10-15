package com.professor.zerion.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.professor.zerion.android.conversation.ConversationCache;
import com.professor.zerion.android.panic.WipePasswordManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.settings.ChatPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.contact.ContactId;

import java.security.Provider;
import java.security.SecureRandomSpi;
import java.security.Security;
import java.util.Collections;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
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

	private static final ContactId PROBE_CONTACT = new ContactId(424242);

	private static WipePasswordManager lastWipePasswords;

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
	public void aCachedConversationDoesNotOutliveItsTest() {
		assertNoCachedConversationAndLeaveOne();
	}

	@Test
	public void aCachedConversationDoesNotOutliveTheNextTest() {
		assertNoCachedConversationAndLeaveOne();
	}

	@Test
	public void aTextScaleDoesNotOutliveItsTest() {
		assertDefaultTextScaleAndLeaveALargerOne();
	}

	@Test
	public void aTextScaleDoesNotOutliveTheNextTest() {
		assertDefaultTextScaleAndLeaveALargerOne();
	}

	@Test
	public void theWipePasswordStoreIsTheOneOfThisApplication() {
		assertWipePasswordStoreIsNewAndRememberIt();
	}

	@Test
	public void theWipePasswordStoreOfTheNextTestIsItsOwn() {
		assertWipePasswordStoreIsNewAndRememberIt();
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

	private static void assertNoCachedConversationAndLeaveOne() {
		ConversationCache cache = ConversationCache.getInstance();
		assertNull("a conversation cached by an earlier test",
				cache.getSnapshot(PROBE_CONTACT));
		cache.put(PROBE_CONTACT, Collections.emptyList());
		assertNotNull(cache.getSnapshot(PROBE_CONTACT));
	}

	private void assertDefaultTextScaleAndLeaveALargerOne() {
		assertEquals("a text scale chosen in an earlier test", 1f,
				ChatPreferences.getGuiFontScale(app), 0f);
		EarlyPrefs.setGuiTextSize(app, ChatPreferences.TEXT_SIZE_EXTRA_LARGE);
		ChatPreferences.invalidateGuiFontScale();
		assertTrue(ChatPreferences.getGuiFontScale(app) > 1f);
	}

	private void assertWipePasswordStoreIsNewAndRememberIt() {
		WipePasswordManager store = WipePasswordManager.getInstance(app);
		assertNotNull(store);
		assertNotSame("the wipe password store of an earlier test",
				lastWipePasswords, store);
		lastWipePasswords = store;
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
