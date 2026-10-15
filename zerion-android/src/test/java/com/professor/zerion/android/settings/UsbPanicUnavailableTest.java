package com.professor.zerion.android.settings;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Looper;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.AntiForensics;
import com.professor.zerion.android.security.AntiForensics.UsbPanicState;
import com.professor.zerion.android.security.HardenedModeEvaluator;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class UsbPanicUnavailableTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Test
	public void onlyAnEnabledPanicThatFailedToArmIsReported() {
		assertFalse(SecurityFragment.usbPanicFailed(false, UsbPanicState.FAILED));
		assertFalse(SecurityFragment.usbPanicFailed(true, UsbPanicState.ARMED));
		assertFalse(SecurityFragment.usbPanicFailed(false, UsbPanicState.OFF));
		assertTrue("enabled but not armed in this process is not active",
				SecurityFragment.usbPanicFailed(true, UsbPanicState.OFF));
		assertTrue(SecurityFragment.usbPanicFailed(true, UsbPanicState.FAILED));
	}

	@Test
	public void theSecurityScreenSaysWhenUsbPanicCouldNotBeTurnedOn() {
		SecurityFragment fragment = host(UsbPanicState.FAILED);
		Context ctx = fragment.requireContext();
		String failure = ctx.getString(R.string.hardened_mode_usb_panic_unavailable);
		confirmHardenedMode(fragment);
		assertTrue(summary(fragment).contains(failure));
		assertEquals(failure, ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void anArmedPanicShowsNoFailure() {
		SecurityFragment fragment = host(UsbPanicState.ARMED);
		Context ctx = fragment.requireContext();
		String failure = ctx.getString(R.string.hardened_mode_usb_panic_unavailable);
		confirmHardenedMode(fragment);
		assertFalse(summary(fragment).contains(failure));
		assertFalse(failure.equals(ShadowToast.getTextOfLatestToast()));
	}

	private static SecurityFragment host(UsbPanicState state) {
		SettingsActivity activity = Robolectric
				.buildActivity(SettingsActivity.class).setup().get();
		SecurityFragment fragment = new SecurityFragment();
		activity.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, fragment).commitNow();
		idle();
		fragment.uiPrefs.edit()
				.putBoolean(HardenedModeEvaluator.PREF_HARDENED_USB_PANIC, true)
				.commit();
		fragment.antiForensics = new AntiForensics(activity) {
			@Override
			public UsbPanicState getUsbPanicState() {
				return state;
			}
		};
		return fragment;
	}

	private static void confirmHardenedMode(SecurityFragment fragment) {
		ShadowDialog.reset();
		fragment.requireView().findViewById(R.id.hardened_mode_card)
				.performClick();
		idle();
		Dialog d = ShadowDialog.getLatestDialog();
		assertNotNull(d);
		((AlertDialog) d).getButton(DialogInterface.BUTTON_POSITIVE)
				.performClick();
		idle();
	}

	private static String summary(SecurityFragment fragment) {
		TextView view = fragment.requireView()
				.findViewById(R.id.hardened_mode_summary);
		return view.getText().toString();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}
}
