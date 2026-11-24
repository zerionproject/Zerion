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
import org.robolectric.fakes.RoboMenuItem;
import org.robolectric.shadows.ShadowDialog;
import org.zerionproject.core.util.IoUtils;

import java.io.File;

import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultNoteBackTest {

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
		vault.showNoteFragment(null);
		idle();
		assertTrue(shown() instanceof SecureNoteFragment);
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

	private void type(String text) {
		((EditText) vault.findViewById(R.id.note_content_input))
				.setText(text);
	}

	private void assertAskedBeforeLeaving() {
		AlertDialog unsaved = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull("the changed note was left without a question",
				unsaved);
		assertTrue(unsaved.isShowing());
		assertTrue(shown() instanceof SecureNoteFragment);
	}

	@Test
	public void backOnTheVaultScreenAsksBeforeLeavingAChangedNote() {
		type("milk");

		vault.onBackPressed();
		idle();

		assertAskedBeforeLeaving();
	}

	@Test
	public void theUpArrowAsksBeforeLeavingAChangedNote() {
		type("milk");

		vault.onOptionsItemSelected(new RoboMenuItem(android.R.id.home));
		idle();

		assertAskedBeforeLeaving();
	}

	@Test
	public void backLeavesANoteThatWasNotChanged() {
		vault.onBackPressed();
		idle();

		assertNull(ShadowDialog.getLatestDialog());
		assertFalse(shown() instanceof SecureNoteFragment);
	}
}
