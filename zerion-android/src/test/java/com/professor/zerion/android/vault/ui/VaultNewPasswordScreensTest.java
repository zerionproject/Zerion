package com.professor.zerion.android.vault.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.view.View;

import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.VaultManager;

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

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultNewPasswordScreensTest {

	private static final String FOUR_AND_PICTURES =
			"pass😀😀😀😀";
	private static final String SEVEN = "Seven7&";
	private static final String GOOD = "Eight8&x";
	private static final String OTHER = "Other8&x";

	static {
		TestAndroidKeyStore.register();
	}

	private VaultActivity vault;

	@Before
	public void setUp() {
		ShadowDialog.reset();
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				VaultActivity.class);
		vault = Robolectric.buildActivity(VaultActivity.class, i).setup()
				.get();
		idle();
	}

	@After
	public void tearDown() {
		Context app = RuntimeEnvironment.getApplication();
		IoUtils.deleteFileOrDir(new File(app.getFilesDir(), "profiles"));
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private Fragment shown() {
		return vault.getSupportFragmentManager()
				.findFragmentById(R.id.vault_container);
	}

	private String rule() {
		return vault.getString(R.string.vault_password_rule);
	}

	private static void type(View root, int id, String text) {
		((SecurePasswordEditText) root.findViewById(id)).setText(text);
		idle();
	}

	private static String error(View root, int id) {
		CharSequence e = ((TextInputLayout) root.findViewById(id)).getError();
		return e == null ? null : e.toString();
	}

	@Test
	public void thePasswordsTheManagerRefusesAreLongEnoughAsTyped() {
		assertTrue(FOUR_AND_PICTURES.length()
				>= VaultManager.MIN_PASSWORD_LENGTH);
		assertFalse(VaultManager.acceptsNewPassword(
				FOUR_AND_PICTURES.toCharArray()));
		assertFalse(VaultManager.acceptsNewPassword(SEVEN.toCharArray()));
		assertTrue(VaultManager.acceptsNewPassword(GOOD.toCharArray()));
	}

	@Test
	public void theFirstVaultScreenNamesTheRuleAndKeepsItsButtonOff() {
		assertTrue(shown() instanceof VaultOnboardingFragment);
		View root = shown().requireView();
		View create = root.findViewById(R.id.create_vault_button);

		type(root, R.id.password_input, FOUR_AND_PICTURES);
		type(root, R.id.confirm_password_input, FOUR_AND_PICTURES);

		assertEquals(rule(), error(root, R.id.password_layout));
		assertFalse(create.isEnabled());

		type(root, R.id.password_input, SEVEN);
		type(root, R.id.confirm_password_input, SEVEN);

		assertEquals(rule(), error(root, R.id.password_layout));
		assertFalse(create.isEnabled());

		type(root, R.id.password_input, GOOD);
		type(root, R.id.confirm_password_input, GOOD);

		assertNull(error(root, R.id.password_layout));
		assertTrue(create.isEnabled());
	}

	@Test
	public void theSetupScreenNamesTheRuleAndKeepsItsButtonOff() {
		vault.showFragment(new VaultSetupFragment(), "vault_setup", false);
		idle();
		assertTrue(shown() instanceof VaultSetupFragment);
		View root = shown().requireView();
		View create = root.findViewById(R.id.vault_create_button);

		type(root, R.id.vault_password_input, FOUR_AND_PICTURES);
		type(root, R.id.vault_confirm_password_input, FOUR_AND_PICTURES);

		assertEquals(rule(), error(root, R.id.vault_password_layout));
		assertFalse(create.isEnabled());

		type(root, R.id.vault_password_input, GOOD);
		type(root, R.id.vault_confirm_password_input, GOOD);

		assertNull(error(root, R.id.vault_password_layout));
		assertTrue(create.isEnabled());
	}

	private AlertDialog changeDialog() {
		vault.showFragment(VaultSettingsFragment.newInstance(), "settings",
				true);
		idle();
		vault.findViewById(R.id.change_password_card).performClick();
		idle();
		AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		assertTrue(dialog.isShowing());
		return dialog;
	}

	private static void submit(AlertDialog dialog, String current,
			String chosen, String again) {
		View root = dialog.getWindow().getDecorView();
		type(root, R.id.current_password_input, current);
		type(root, R.id.new_password_input, chosen);
		type(root, R.id.confirm_password_input, again);
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
		idle();
	}

	@Test
	public void theChangeDialogStaysOpenAndNamesTheRule() {
		AlertDialog dialog = changeDialog();
		View root = dialog.getWindow().getDecorView();

		submit(dialog, GOOD, FOUR_AND_PICTURES, FOUR_AND_PICTURES);

		assertTrue("the dialog closed on a refused password",
				dialog.isShowing());
		assertEquals(rule(), error(root, R.id.new_password_layout));
		assertNull(error(root, R.id.confirm_password_layout));
		assertEquals(FOUR_AND_PICTURES.length(), ((SecurePasswordEditText)
				root.findViewById(R.id.new_password_input)).length());
	}

	@Test
	public void theChangeDialogStaysOpenWhenTheTwoPasswordsDiffer() {
		AlertDialog dialog = changeDialog();
		View root = dialog.getWindow().getDecorView();

		submit(dialog, GOOD, GOOD, OTHER);

		assertTrue("the dialog closed on a mismatch", dialog.isShowing());
		assertEquals(vault.getString(R.string.vault_password_mismatch),
				error(root, R.id.confirm_password_layout));
		assertNull(error(root, R.id.new_password_layout));
	}

	@Test
	public void theChangeDialogDropsAnEarlierErrorAndClosesOnAGoodEntry() {
		AlertDialog dialog = changeDialog();
		View root = dialog.getWindow().getDecorView();
		submit(dialog, GOOD, SEVEN, SEVEN);
		assertTrue(dialog.isShowing());
		assertEquals(rule(), error(root, R.id.new_password_layout));

		submit(dialog, GOOD, OTHER, OTHER);

		assertFalse(dialog.isShowing());
		assertEquals(0, ((SecurePasswordEditText)
				root.findViewById(R.id.new_password_input)).length());
		assertEquals(0, ((SecurePasswordEditText)
				root.findViewById(R.id.current_password_input)).length());
	}
}
