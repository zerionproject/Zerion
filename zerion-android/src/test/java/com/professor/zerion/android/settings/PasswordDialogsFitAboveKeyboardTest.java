package com.professor.zerion.android.settings;

import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureDialogs;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.account.AccountManager;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class PasswordDialogsFitAboveKeyboardTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Before
	public void noWipePasswordFromEarlierTests() throws Exception {
		java.lang.reflect.Field f = com.professor.zerion.android.panic
				.WipePasswordManager.class.getDeclaredField("instance");
		f.setAccessible(true);
		f.set(null, null);
	}

	@Test
	public void theWipePasswordDialogKeepsItsButtonsAboveTheKeyboard() {
		SecurityFragment fragment = host();
		fragment.requireView().findViewById(R.id.wipe_password_card)
				.performClick();
		idle();
		AlertDialog d = latestDialog();

		assertResizesForTheKeyboard(d);
		View standardMessage = d.findViewById(
				androidx.appcompat.R.id.contentPanel);
		assertTrue("the message scrolls with the fields, so the buttons "
						+ "keep their room",
				standardMessage == null
						|| standardMessage.getVisibility() == View.GONE);
		TextView message = d.findViewById(R.id.password_message);
		assertNotNull(message);
		assertEquals(View.VISIBLE, message.getVisibility());
		assertEquals(fragment.getString(
						R.string.wipe_password_dialog_message),
				message.getText().toString());
		assertTrue(SecureDialogs.isProtected(d.getWindow()));
		d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
		idle();
	}

	@Test
	public void theDecoyCodeDialogKeepsItsButtonsAboveTheKeyboard() {
		SecurityFragment fragment = host();
		fragment.requireView().findViewById(R.id.decoy_set_code_card)
				.performClick();
		idle();
		AlertDialog d = latestDialog();

		assertResizesForTheKeyboard(d);
		assertTrue(SecureDialogs.isProtected(d.getWindow()));
		d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
		idle();
	}

	private static void assertResizesForTheKeyboard(AlertDialog d) {
		assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
				d.getWindow().getAttributes().softInputMode
						& WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
		assertNotNull(d.getButton(AlertDialog.BUTTON_POSITIVE));
		assertNotNull(d.getButton(AlertDialog.BUTTON_NEGATIVE));
	}

	private static SecurityFragment host() {
		SettingsActivity activity = Robolectric
				.buildActivity(SettingsActivity.class).setup().get();
		SecurityFragment fragment = new SecurityFragment();
		activity.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, fragment).commitNow();
		idle();
		AccountManager noAccount = mock(AccountManager.class);
		when(noAccount.accountExists()).thenReturn(false);
		fragment.accountManager = noAccount;
		return fragment;
	}

	private static AlertDialog latestDialog() {
		Dialog d = ShadowDialog.getLatestDialog();
		assertNotNull(d);
		assertTrue(d.isShowing());
		return (AlertDialog) d;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}
}
