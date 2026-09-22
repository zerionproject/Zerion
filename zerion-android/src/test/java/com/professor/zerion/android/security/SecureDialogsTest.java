package com.professor.zerion.android.security;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import javax.annotation.Nullable;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * AND-03: FLAG_SECURE is per window, so a dialog must carry it itself. A
 * dialog inherits its host's protection, and a dialog holding a secret is
 * protected even when the host is not; an ordinary dialog under an
 * unprotected host stays capturable, respecting the user's preference.
 */
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SecureDialogsTest {

	private FragmentActivity host(boolean secure) {
		FragmentActivity a = Robolectric.buildActivity(FragmentActivity.class)
				.setup().get();
		a.setTheme(com.google.android.material.R.style
				.Theme_MaterialComponents_DayNight_NoActionBar);
		if (secure) {
			a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
		}
		SecureDialogs.install(a);
		return a;
	}

	private static EditText passwordField(FragmentActivity a) {
		EditText e = new EditText(a);
		e.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_VARIATION_PASSWORD);
		return e;
	}

	@Test
	public void dialogInheritsTheHostProtection() {
		FragmentActivity a = host(true);
		AlertDialog d = new SecureAlertDialogBuilder(a).setMessage("hello")
				.create();
		d.show();
		assertTrue("dialog under a protected host must be protected",
				SecureDialogs.isProtected(d.getWindow()));
	}

	/** The defect itself: a dialog window does not inherit its host's flag. */
	@Test
	public void plainBuilderDialogDoesNotInheritProtection() {
		FragmentActivity a = host(true);
		AlertDialog d = new com.google.android.material.dialog
				.MaterialAlertDialogBuilder(a).setMessage("hello").create();
		d.show();
		assertFalse("FLAG_SECURE is per window: the raw dialog is capturable",
				SecureDialogs.isProtected(d.getWindow()));
	}

	@Test
	public void ordinaryDialogUnderUnprotectedHostIsNotBlocked() {
		FragmentActivity a = host(false);
		AlertDialog d = new SecureAlertDialogBuilder(a).setMessage("hello")
				.create();
		d.show();
		assertFalse("preference off: an ordinary dialog is not blocked",
				SecureDialogs.isProtected(d.getWindow()));
	}

	@Test
	public void dialogWithPasswordInputIsAlwaysProtected() {
		FragmentActivity a = host(false);
		LinearLayout box = new LinearLayout(a);
		box.addView(new TextView(a));
		box.addView(passwordField(a));
		AlertDialog d = new SecureAlertDialogBuilder(a).setView(box).create();
		d.show();
		assertTrue("a password prompt is protected regardless of preference",
				SecureDialogs.isProtected(d.getWindow()));
	}

	@Test
	public void visiblePasswordVariantIsStillASecret() {
		FragmentActivity a = host(false);
		EditText e = new EditText(a);
		e.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
		AlertDialog d = new SecureAlertDialogBuilder(a).setView(e).create();
		d.show();
		assertTrue("show-password mode must not lift the protection",
				SecureDialogs.isProtected(d.getWindow()));
	}

	@Test
	public void numericPinInputIsASecret() {
		FragmentActivity a = host(false);
		EditText e = new EditText(a);
		e.setInputType(InputType.TYPE_CLASS_NUMBER
				| InputType.TYPE_NUMBER_VARIATION_PASSWORD);
		AlertDialog d = new SecureAlertDialogBuilder(a).setView(e).create();
		d.show();
		assertTrue(SecureDialogs.isProtected(d.getWindow()));
	}

	@Test
	public void explicitlyMarkedSecretDialogIsProtected() {
		FragmentActivity a = host(false);
		AlertDialog d = new SecureAlertDialogBuilder(a).protectSecrets()
				.setMessage("recovery phrase words").create();
		d.show();
		assertTrue(SecureDialogs.isProtected(d.getWindow()));
	}

	@Test
	public void dialogFragmentInheritsTheHostProtectionThroughTheHook() {
		FragmentActivity a = host(true);
		PlainDialogFragment f = new PlainDialogFragment();
		f.show(a.getSupportFragmentManager(), "plain");
		a.getSupportFragmentManager().executePendingTransactions();
		Dialog d = f.getDialog();
		assertNotNull(d);
		assertTrue("the fragment hook protects the dialog window",
				SecureDialogs.isProtected(d.getWindow()));
	}

	@Test
	public void dialogFragmentWithPasswordInputIsProtectedUnderUnprotectedHost() {
		FragmentActivity a = host(false);
		PasswordDialogFragment f = new PasswordDialogFragment();
		f.show(a.getSupportFragmentManager(), "pw");
		a.getSupportFragmentManager().executePendingTransactions();
		Dialog d = f.getDialog();
		assertNotNull(d);
		assertTrue(SecureDialogs.isProtected(d.getWindow()));
	}

	@Test
	public void plainDialogFragmentUnderUnprotectedHostIsNotBlocked() {
		FragmentActivity a = host(false);
		PlainDialogFragment f = new PlainDialogFragment();
		f.show(a.getSupportFragmentManager(), "plain");
		a.getSupportFragmentManager().executePendingTransactions();
		Dialog d = f.getDialog();
		assertNotNull(d);
		assertFalse(SecureDialogs.isProtected(d.getWindow()));
	}

	public static class PlainDialogFragment extends DialogFragment {
		@Override
		public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
			Dialog d = new Dialog(requireContext());
			d.setContentView(new TextView(requireContext()));
			return d;
		}
	}

	public static class PasswordDialogFragment extends DialogFragment {
		@Override
		public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
			Dialog d = new Dialog(requireContext());
			EditText e = new EditText(requireContext());
			e.setInputType(InputType.TYPE_CLASS_TEXT
					| InputType.TYPE_TEXT_VARIATION_PASSWORD);
			d.setContentView(e);
			return d;
		}
	}
}
