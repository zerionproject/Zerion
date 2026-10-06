package com.professor.zerion.android.vault.ui;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.WindowManager;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultScreenProtectionTest {

	static {
		TestAndroidKeyStore.register();
	}

	private SharedPreferences prefs;

	@Before
	public void setUp() {
		Context ctx = ApplicationProvider.getApplicationContext();
		prefs = ctx.getSharedPreferences("vault_screen_protection_test",
				Context.MODE_PRIVATE);
		prefs.edit().clear().commit();
	}

	private VaultProbeScreens.ProbeHost host() {
		VaultProbeScreens.ProbeHost host = Robolectric
				.buildActivity(VaultProbeScreens.ProbeHost.class).setup()
				.get();
		VaultScreenProtection.install(host, prefs, () -> {
			if (VaultScreenProtection.required(
					host.getSupportFragmentManager(), prefs)) {
				host.getWindow().addFlags(
						WindowManager.LayoutParams.FLAG_SECURE);
			} else {
				host.getWindow().clearFlags(
						WindowManager.LayoutParams.FLAG_SECURE);
			}
		});
		return host;
	}

	private static void show(FragmentActivity host, Fragment f) {
		host.getSupportFragmentManager().beginTransaction()
				.replace(android.R.id.content, f).commitNow();
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static boolean secure(Activity a) {
		return (a.getWindow().getAttributes().flags
				& WindowManager.LayoutParams.FLAG_SECURE) != 0;
	}

	@Test
	public void aRecoveryPhraseScreenIsAlwaysProtected() {
		prefs.edit().putBoolean(VaultScreenProtection.PREF_HIDE_CONTENT, false)
				.commit();
		VaultProbeScreens.ProbeHost host = host();
		assertFalse(secure(host));
		show(host, new VaultProbeScreens.ProbeSeedScreen());
		assertTrue(secure(host));
	}

	@Test
	public void aVaultScreenFollowsTheVaultsHideContentSetting() {
		VaultProbeScreens.ProbeHost host = host();
		show(host, new VaultProbeScreens.ProbeVaultScreen());
		assertTrue(secure(host));
		show(host, new Fragment());
		prefs.edit().putBoolean(VaultScreenProtection.PREF_HIDE_CONTENT, false)
				.commit();
		show(host, new VaultProbeScreens.ProbeVaultScreen());
		assertFalse(secure(host));
	}

	@Test
	public void leavingTheVaultRestoresTheHostsPolicy() {
		VaultProbeScreens.ProbeHost host = host();
		show(host, new VaultProbeScreens.ProbeSeedScreen());
		assertTrue(secure(host));
		show(host, new Fragment());
		assertFalse(secure(host));
	}

	@Test
	public void onlyVaultScreensAreRecognised() {
		assertTrue(VaultScreenProtection.isVaultScreen(
				new VaultProbeScreens.ProbeVaultScreen()));
		assertTrue(VaultScreenProtection.isWalletScreen(
				new VaultProbeScreens.ProbeSeedScreen()));
		assertFalse(VaultScreenProtection.isWalletScreen(
				new VaultProbeScreens.ProbeVaultScreen()));
		assertFalse(VaultScreenProtection.isVaultScreen(new Fragment()));
		assertFalse(VaultScreenProtection.isVaultScreen(null));
	}
}
