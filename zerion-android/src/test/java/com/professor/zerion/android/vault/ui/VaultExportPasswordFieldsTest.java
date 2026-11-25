package com.professor.zerion.android.vault.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.widget.EditText;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.core.util.IoUtils;

import java.io.File;
import java.lang.reflect.Field;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultExportPasswordFieldsTest {

	private static final String TYPED = "export pass 4$Dd";

	static {
		TestAndroidKeyStore.register();
	}

	private VaultActivity vault;
	private VaultSettingsFragment settings;
	private AlertDialog dialog;
	private EditText password;
	private EditText confirm;

	@Before
	public void setUp() {
		ShadowDialog.reset();
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				VaultActivity.class);
		vault = Robolectric.buildActivity(VaultActivity.class, i).setup()
				.get();
		idle();
		vault.showFragment(VaultSettingsFragment.newInstance(), "settings",
				true);
		idle();
		Fragment shown = vault.getSupportFragmentManager()
				.findFragmentById(R.id.vault_container);
		assertTrue(shown instanceof VaultSettingsFragment);
		settings = (VaultSettingsFragment) shown;
		vault.findViewById(R.id.export_card).performClick();
		idle();
		((AlertDialog) ShadowDialog.getLatestDialog())
				.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		dialog = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		password = dialog.findViewById(R.id.export_password_input);
		confirm = dialog.findViewById(R.id.export_password_confirm);
		assertNotNull(password);
		assertNotNull(confirm);
	}

	@After
	public void tearDown() {
		Context app = RuntimeEnvironment.getApplication();
		IoUtils.deleteFileOrDir(new File(app.getFilesDir(), "profiles"));
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private char[] pending() throws Exception {
		Field f = VaultSettingsFragment.class
				.getDeclaredField("pendingExportPassword");
		f.setAccessible(true);
		return (char[]) f.get(settings);
	}

	private void assertFieldsEmpty() {
		assertEquals(0, password.getText().length());
		assertEquals(0, confirm.getText().length());
	}

	@Test
	public void theFieldsAreEmptiedOnceTheirPasswordsAreRead()
			throws Exception {
		password.setText(TYPED);
		confirm.setText(TYPED + "x");

		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();

		assertFieldsEmpty();
		assertNull(pending());
	}

	private static String shownText(android.view.View view) {
		if (view.getId() == com.google.android.material.R.id.snackbar_text
				&& view instanceof android.widget.TextView) {
			return ((android.widget.TextView) view).getText().toString();
		}
		if (view instanceof android.view.ViewGroup) {
			android.view.ViewGroup group = (android.view.ViewGroup) view;
			for (int i = 0; i < group.getChildCount(); i++) {
				String found = shownText(group.getChildAt(i));
				if (found != null) return found;
			}
		}
		return null;
	}

	private void assertRefusedWithTheRule(String typed) throws Exception {
		password.setText(typed);
		confirm.setText(typed);

		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();

		assertEquals(vault.getString(R.string.vault_password_rule),
				shownText(vault.getWindow().getDecorView()));
		assertNull("the picker was opened for " + typed, pending());
		assertFieldsEmpty();
	}

	@Test
	public void anExportPasswordTooShortIsRefusedWithTheRule()
			throws Exception {
		assertRefusedWithTheRule("Seven7&");
	}

	@Test
	public void anExportPasswordShortWithoutItsEmojiIsRefusedWithTheRule()
			throws Exception {
		assertRefusedWithTheRule("pass\uD83D\uDE00\uD83D\uDE00");
	}

	@Test
	public void theFieldsAreEmptiedWhenTheDialogIsCancelled()
			throws Exception {
		password.setText(TYPED);
		confirm.setText(TYPED);

		dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
		idle();

		assertFalse(dialog.isShowing());
		assertFieldsEmpty();
		assertNull(pending());
	}

	@Test
	public void thePasswordHeldForThePickerIsWipedWhenTheScreenGoesAway()
			throws Exception {
		password.setText(TYPED);
		confirm.setText(TYPED);
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
		assertFieldsEmpty();
		char[] held = pending();
		assertNotNull("no password was held for the picker", held);
		assertEquals(TYPED, new String(held));

		vault.getSupportFragmentManager().popBackStackImmediate();
		idle();

		assertArrayEquals(new char[TYPED.length()], held);
		assertNull(pending());
	}
}
