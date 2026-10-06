package com.professor.zerion.android.navdrawer;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.WindowManager;

import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.ui.VaultProbeScreens;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.settings.SecurityFragment.PREF_SCREENSHOT_PROTECTION;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class NavDrawerVaultProtectionTest {

	static {
		TestAndroidKeyStore.register();
	}

	private SharedPreferences uiPrefs() {
		ZerionApplication app = ApplicationProvider.getApplicationContext();
		return app.getApplicationComponent().uiPreferences();
	}

	@After
	public void tearDown() {
		uiPrefs().edit().remove(PREF_SCREENSHOT_PROTECTION).commit();
	}

	private static boolean secure(Activity a) {
		return (a.getWindow().getAttributes().flags
				& WindowManager.LayoutParams.FLAG_SECURE) != 0;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	@Test
	public void aWalletScreenInTheMainScreenIsProtectedWithScreenshotsAllowed() {
		uiPrefs().edit().putBoolean(PREF_SCREENSHOT_PROTECTION, false)
				.commit();
		ActivityController<NavDrawerActivity> c =
				Robolectric.buildActivity(NavDrawerActivity.class).setup();
		idle();
		NavDrawerActivity a = c.get();
		assertFalse("the user's choice applies to the chats",
				secure(a));

		a.showNextFragment(new VaultProbeScreens.ProbeSeedScreen());
		idle();
		assertTrue("a wallet screen is protected in the main screen",
				secure(a));

		c.pause().stop().restart().start().resume();
		idle();
		assertTrue("and stays protected when the app is shown again",
				secure(a));

		a.getSupportFragmentManager().popBackStackImmediate();
		idle();
		assertFalse("the user's choice applies again outside the vault",
				secure(a));
	}
}
