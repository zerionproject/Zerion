package com.professor.zerion.android.settings;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Looper;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

/**
 * Enabling "erase after repeated failed logins" is an explicit opt-in: the
 * switch alone never turns the policy on. Cancelling or dismissing the
 * confirmation leaves the policy off and the switch off; only the confirm
 * button turns it on, and turning the switch off needs no confirmation.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class WipeOnFailedLoginsOptInTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Test
	public void theSwitchAloneNeverEnablesErasure() {
		SecurityFragment fragment = host();
		SwitchMaterial sw = fragment.requireView()
				.findViewById(R.id.wipe_on_failed_logins_switch);
		assertNotNull(sw);
		assertFalse(fragment.bruteForceProtection.isWipeOnRepeatedFailures());

		press(sw);
		AlertDialog dialog = latestDialog();
		assertFalse(fragment.bruteForceProtection.isWipeOnRepeatedFailures());
		dialog.getButton(DialogInterface.BUTTON_NEGATIVE).performClick();
		idle();
		assertFalse(fragment.bruteForceProtection.isWipeOnRepeatedFailures());
		assertFalse(sw.isChecked());

		press(sw);
		dialog = latestDialog();
		dialog.cancel();
		idle();
		assertFalse(fragment.bruteForceProtection.isWipeOnRepeatedFailures());
		assertFalse(sw.isChecked());

		sw.setChecked(true);
		idle();
		assertFalse("a programmatic change is not a user opt-in",
				fragment.bruteForceProtection.isWipeOnRepeatedFailures());
		sw.setChecked(false);
	}

	@Test
	public void onlyTheConfirmButtonEnablesErasureAndTheSwitchDisablesIt() {
		SecurityFragment fragment = host();
		SwitchMaterial sw = fragment.requireView()
				.findViewById(R.id.wipe_on_failed_logins_switch);
		press(sw);
		AlertDialog dialog = latestDialog();
		dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		idle();
		assertTrue(fragment.bruteForceProtection.isWipeOnRepeatedFailures());
		assertTrue(sw.isChecked());

		press(sw);
		idle();
		assertFalse(fragment.bruteForceProtection.isWipeOnRepeatedFailures());
		assertFalse(sw.isChecked());
		fragment.bruteForceProtection.setWipeOnRepeatedFailures(false);
	}

	private static SecurityFragment host() {
		SettingsActivity activity = Robolectric
				.buildActivity(SettingsActivity.class).setup().get();
		SecurityFragment fragment = new SecurityFragment();
		activity.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, fragment).commitNow();
		idle();
		fragment.bruteForceProtection.setWipeOnRepeatedFailures(false);
		return fragment;
	}

	private static void press(SwitchMaterial sw) {
		sw.setPressed(true);
		sw.performClick();
		sw.setPressed(false);
		idle();
	}

	private static AlertDialog latestDialog() {
		Dialog d = ShadowDialog.getLatestDialog();
		assertNotNull("expected the confirmation dialog", d);
		assertTrue(d.isShowing());
		return (AlertDialog) d;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}
}
