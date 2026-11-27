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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultEditorBackTest {

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
		vault.showNextFragment(TextEditorFragment.newInstance());
		idle();
		assertTrue(shown() instanceof TextEditorFragment);
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
		((EditText) vault.findViewById(R.id.document_content_input))
				.setText(text);
	}

	private void holdForASave() throws Exception {
		Field request = TextEditorFragment.class
				.getDeclaredField("saveRequest");
		request.setAccessible(true);
		request.setLong(shown(), 7L);
	}

	@Test
	public void backOnTheVaultScreenAsksBeforeLeavingAChangedDocument() {
		type("first draft");

		vault.onBackPressed();
		idle();

		AlertDialog unsaved = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull("the changed document was left without a question",
				unsaved);
		assertTrue(unsaved.isShowing());
		assertTrue(shown() instanceof TextEditorFragment);
	}

	@Test
	public void backOnTheVaultScreenDoesNotLeaveADocumentWhileItIsSaved()
			throws Exception {
		type("first draft");
		holdForASave();

		vault.onBackPressed();
		idle();

		assertTrue("the editor was left during a save",
				shown() instanceof TextEditorFragment);
		assertNull(ShadowDialog.getLatestDialog());
	}

	@Test
	public void backDoesNotLeaveAnUnchangedDocumentWhileItIsSaved()
			throws Exception {
		holdForASave();

		vault.onBackPressed();
		idle();

		assertTrue(shown() instanceof TextEditorFragment);
		assertNull(ShadowDialog.getLatestDialog());
	}

	@Test
	public void backLeavesADocumentThatWasNotChanged() {
		vault.onBackPressed();
		idle();

		assertNull(ShadowDialog.getLatestDialog());
		assertFalse(shown() instanceof TextEditorFragment);
	}

	@Test
	public void discardingTheChangesLeavesTheEditor() {
		type("first draft");
		vault.onBackPressed();
		idle();
		AlertDialog unsaved = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull(unsaved);

		unsaved.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
		idle();

		assertFalse(shown() instanceof TextEditorFragment);
	}
}
