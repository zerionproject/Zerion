package com.professor.zerion.android.security;

import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class HardenedBlockActivityTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Test
	public void theBlockScreenStartsNothingWhenSignedOut() {
		Intent i = new Intent(RuntimeEnvironment.getApplication(),
				HardenedBlockActivity.class);
		i.putExtra(HardenedBlockActivity.EXTRA_RESULT_CODE,
				SecureBootGuard.RESULT_VERIFIED_BOOT_NOT_GREEN);
		ActivityController<HardenedBlockActivity> c =
				Robolectric.buildActivity(HardenedBlockActivity.class, i);
		c.create().start().resume();
		HardenedBlockActivity a = c.get();
		assertNull("no login relaunch", shadowOf(a).getNextStartedActivity());
		assertFalse(a.isFinishing());
		c.pause().stop().destroy();
	}

	private static final long TIMEOUT_MS = 10_000;

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	@Nullable
	private static EditText findEditText(View v) {
		if (v instanceof EditText) return (EditText) v;
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				EditText e = findEditText(g.getChildAt(i));
				if (e != null) return e;
			}
		}
		return null;
	}

	private String disableWith(AccountManager am, String typed)
			throws Exception {
		Intent i = new Intent(RuntimeEnvironment.getApplication(),
				HardenedBlockActivity.class);
		i.putExtra(HardenedBlockActivity.EXTRA_RESULT_CODE,
				SecureBootGuard.RESULT_VERIFIED_BOOT_NOT_GREEN);
		ActivityController<HardenedBlockActivity> c =
				Robolectric.buildActivity(HardenedBlockActivity.class, i);
		c.create().start().resume();
		HardenedBlockActivity a = c.get();
		SharedPreferences prefs = RuntimeEnvironment.getApplication()
				.getSharedPreferences("hardened-block-test",
						Context.MODE_PRIVATE);
		prefs.edit().putBoolean(HardenedModeEvaluator.PREF_HARDENED_BOOT, true)
				.commit();
		a.uiPrefs = prefs;
		a.accountManager = am;
		ShadowToast.reset();

		a.findViewById(R.id.hardenedBlockDisableButton).performClick();
		idle();
		AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		EditText field = findEditText(dialog.getWindow().getDecorView());
		assertNotNull("the dialog asks for the password", field);
		field.setText(typed);
		dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();

		long end = System.currentTimeMillis() + TIMEOUT_MS;
		while (System.currentTimeMillis() < end && !a.isFinishing()
				&& ShadowToast.getLatestToast() == null) {
			idle();
			Thread.sleep(10);
		}
		idle();
		String outcome = a.isFinishing() ? "turned off" : "stays on";
		boolean stillOn = prefs.getBoolean(
				HardenedModeEvaluator.PREF_HARDENED_BOOT, false);
		c.pause().stop().destroy();
		return outcome + ", setting " + (stillOn ? "on" : "off");
	}

	private static AccountManager signedIn() {
		AccountManager am = mock(AccountManager.class);
		when(am.accountExists()).thenReturn(true);
		when(am.hasDatabaseKey()).thenReturn(true);
		return am;
	}

	@Test
	public void whileSignedInThePasswordIsOnlyChecked() throws Exception {
		AccountManager am = signedIn();
		AtomicReference<String> given = new AtomicReference<>();
		doAnswer(inv -> {
			given.set(new String((char[]) inv.getArgument(0)));
			return null;
		}).when(am).verifyPassword(any());
		assertEquals("turned off, setting off",
				disableWith(am, "the account password"));
		assertEquals("the account password", given.get());
		verify(am, never()).signIn(any());
	}

	@Test
	public void whileSignedInAPasswordTheCheckRefusesKeepsTheMode()
			throws Exception {
		AccountManager am = signedIn();
		doAnswer(inv -> {
			throw new DecryptionException(DecryptionResult.INVALID_CIPHERTEXT);
		}).when(am).verifyPassword(any());
		assertEquals("stays on, setting on",
				disableWith(am, "another profile's password"));
		assertEquals(RuntimeEnvironment.getApplication().getString(
				R.string.hardened_block_password_wrong),
				ShadowToast.getTextOfLatestToast());
		verify(am, never()).signIn(any());
	}

	@Test
	public void withNoAccountSignedInThePasswordSignsIn() throws Exception {
		AccountManager am = mock(AccountManager.class);
		when(am.accountExists()).thenReturn(true);
		when(am.hasDatabaseKey()).thenReturn(false);
		assertEquals("turned off, setting off",
				disableWith(am, "the account password"));
		verify(am).signIn(any());
		verify(am, never()).verifyPassword(any());
	}
}
