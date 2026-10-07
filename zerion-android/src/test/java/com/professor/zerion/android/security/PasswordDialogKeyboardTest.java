package com.professor.zerion.android.security;

import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.NestedScrollView;
import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class PasswordDialogKeyboardTest {

	private static FragmentActivity host() {
		FragmentActivity a = Robolectric.buildActivity(FragmentActivity.class)
				.setup().get();
		a.setTheme(R.style.ZerionTheme_NoActionBar);
		return a;
	}

	private static int adjust(AlertDialog d) {
		return d.getWindow().getAttributes().softInputMode
				& WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST;
	}

	@Test
	public void thePasswordDialogLayoutScrollsWhenTheKeyboardLeavesLittleRoom() {
		FragmentActivity a = host();
		View v = LayoutInflater.from(a).inflate(R.layout.dialog_password,
				null);
		assertTrue(v instanceof NestedScrollView);
		assertNotNull(v.findViewById(R.id.password_input_1));
		assertNotNull(v.findViewById(R.id.password_input_2));
		assertNotNull(v.findViewById(R.id.show_password_checkbox));
		assertNotNull(v.findViewById(R.id.warning_text));
		assertNotNull(v.findViewById(R.id.password_message));
	}

	@Test
	public void aPasswordDialogShrinksAboveTheKeyboardAndStaysSecure() {
		FragmentActivity a = host();
		View v = LayoutInflater.from(a).inflate(R.layout.dialog_password,
				null);
		AlertDialog d = new SecureAlertDialogBuilder(a)
				.fitAboveKeyboard()
				.setTitle("t")
				.setView(v)
				.setPositiveButton(android.R.string.ok, null)
				.setNegativeButton(android.R.string.cancel, null)
				.create();
		d.show();

		assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
				adjust(d));
		assertTrue(SecureDialogs.isProtected(d.getWindow()));
	}

	@Test
	public void otherDialogsKeepTheirKeyboardBehaviour() {
		FragmentActivity a = host();
		AlertDialog d = new SecureAlertDialogBuilder(a)
				.setTitle("t")
				.setView(LayoutInflater.from(a).inflate(
						R.layout.dialog_password, null))
				.create();
		d.show();

		assertNotEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
				adjust(d));
	}
}
