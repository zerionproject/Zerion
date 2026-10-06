package com.professor.zerion.android.security;

import android.app.Application;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import com.professor.zerion.R;
import com.professor.zerion.android.panic.WipePasswordManager;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.core.api.account.AccountManager;
import org.zerionproject.core.api.crypto.DecryptionException;
import org.zerionproject.core.api.crypto.DecryptionResult;

import javax.annotation.Nullable;

import androidx.appcompat.app.AlertDialog;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class HardenedBlockPolicyTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String DURESS = "duress password 8&Gh";
	private static final long TIMEOUT_MS = 10_000;

	private final Application app = RuntimeEnvironment.getApplication();

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

	private static void freshDuressManager() throws Exception {
		java.lang.reflect.Field f = com.professor.zerion.android.panic
				.WipePasswordManager.class.getDeclaredField("instance");
		f.setAccessible(true);
		f.set(null, null);
	}

	private void setDuressPassword() throws Exception {
		freshDuressManager();
		boolean[] set = {false};
		Thread t = new Thread(() -> {
			WipePasswordManager wpm = WipePasswordManager.getInstance(app);
			set[0] = wpm != null && wpm.setWipePassword(DURESS.toCharArray());
		});
		t.start();
		t.join();
	}

	private static int calls(Object mock, String method) {
		int n = 0;
		for (org.mockito.invocation.Invocation i :
				mockingDetails(mock).getInvocations()) {
			if (i.getMethod().getName().equals(method)) n++;
		}
		return n;
	}

	@Test
	public void theDuressPasswordAtTheBlockScreenErasesEveryAccount()
			throws Exception {
		setDuressPassword();
		AccountManager am = mock(AccountManager.class);
		when(am.accountExists()).thenReturn(true);
		when(am.hasDatabaseKey()).thenReturn(false);
		doAnswer(inv -> {
			throw new DecryptionException(DecryptionResult.INVALID_PASSWORD);
		}).when(am).signIn(any());

		Intent i = new Intent(app, HardenedBlockActivity.class);
		i.putExtra(HardenedBlockActivity.EXTRA_RESULT_CODE,
				SecureBootGuard.RESULT_VERIFIED_BOOT_NOT_GREEN);
		ActivityController<HardenedBlockActivity> c =
				Robolectric.buildActivity(HardenedBlockActivity.class, i);
		c.create().start().resume();
		HardenedBlockActivity a = c.get();
		SharedPreferences prefs = app.getSharedPreferences(
				"hardened-block-policy-test", Context.MODE_PRIVATE);
		a.uiPrefs = prefs;
		a.accountManager = am;

		a.findViewById(R.id.hardenedBlockDisableButton).performClick();
		shadowOf(Looper.getMainLooper()).idle();
		AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		EditText field = findEditText(dialog.getWindow().getDecorView());
		assertNotNull(field);
		field.setText(DURESS);
		dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();

		long end = System.currentTimeMillis() + TIMEOUT_MS;
		while (System.currentTimeMillis() < end
				&& calls(am, "deleteAccount") == 0) {
			shadowOf(Looper.getMainLooper()).idle();
			Thread.sleep(10);
		}
		shadowOf(Looper.getMainLooper()).idle();
		assertEquals("keys shredded 1, accounts deleted 1",
				"keys shredded " + calls(am, "shredDatabaseKey")
						+ ", accounts deleted " + calls(am, "deleteAccount"));
		c.pause().stop().destroy();
	}
}
