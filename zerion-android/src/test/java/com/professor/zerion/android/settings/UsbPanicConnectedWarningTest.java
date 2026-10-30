package com.professor.zerion.android.settings;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Looper;
import android.widget.ListView;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.AntiForensics;
import com.professor.zerion.android.security.HardenedModeEvaluator;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class UsbPanicConnectedWarningTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Test
	public void theWarningIsAddedOnlyWhileADataConnectionIsActive() {
		Context ctx = RuntimeEnvironment.getApplication();
		String base = ctx.getString(R.string.hardened_mode_usb_confirm_message);
		String warning =
				ctx.getString(R.string.hardened_mode_usb_connected_warning);
		assertEquals(base, SecurityFragment.usbConfirmMessage(ctx, false));
		assertEquals(base + "\n\n" + warning,
				SecurityFragment.usbConfirmMessage(ctx, true));
	}

	@Test
	public void enablingUsbPanicWhileConnectedWarnsBeforeTheScopeIsChosen() {
		SecurityFragment fragment = host(true);
		String warning = fragment.requireContext()
				.getString(R.string.hardened_mode_usb_connected_warning);
		assertTrue(confirmMessage(fragment).contains(warning));
		assertFalse("nothing is stored before the scope is chosen",
				fragment.uiPrefs.getBoolean(
						HardenedModeEvaluator.PREF_HARDENED_USB_PANIC, false));
	}

	@Test
	public void enablingUsbPanicWithoutAConnectionShowsNoWarning() {
		SecurityFragment fragment = host(false);
		String warning = fragment.requireContext()
				.getString(R.string.hardened_mode_usb_connected_warning);
		assertFalse(confirmMessage(fragment).contains(warning));
	}

	private static SecurityFragment host(boolean connected) {
		SettingsActivity activity = Robolectric
				.buildActivity(SettingsActivity.class).setup().get();
		SecurityFragment fragment = new SecurityFragment();
		activity.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, fragment).commitNow();
		idle();
		fragment.uiPrefs.edit()
				.putBoolean(HardenedModeEvaluator.PREF_HARDENED_USB_PANIC, false)
				.commit();
		fragment.antiForensics = new AntiForensics(activity) {
			@Override
			public boolean isDataTransferActive() {
				return connected;
			}
		};
		return fragment;
	}

	private static String confirmMessage(SecurityFragment fragment) {
		ShadowDialog.reset();
		fragment.requireView().findViewById(R.id.hardened_mode_card)
				.performClick();
		idle();
		AlertDialog choices = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(choices);
		ListView list = choices.getListView();
		list.performItemClick(list.getAdapter().getView(2, null, list), 2,
				list.getAdapter().getItemId(2));
		idle();
		choices.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		idle();
		Dialog confirm = ShadowDialog.getLatestDialog();
		assertNotNull(confirm);
		assertTrue(confirm != choices);
		TextView message = confirm.findViewById(android.R.id.message);
		assertNotNull(message);
		return message.getText().toString();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}
}
