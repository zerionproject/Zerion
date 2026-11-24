package com.professor.zerion.android.vault.ui;

import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Looper;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.model.VaultItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.fakes.RoboMenuItem;
import org.robolectric.shadows.ShadowDialog;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class TextEditorSaveTest {

	private static final String TAG = "editor";

	private static VaultViewModel model;

	static {
		TestAndroidKeyStore.register();
	}

	public static class Host extends AppCompatActivity
			implements BaseFragment.BaseFragmentListener {

		@Override
		protected void onCreate(Bundle savedInstanceState) {
			setTheme(R.style.ZerionTheme_NoActionBar);
			super.onCreate(savedInstanceState);
			FrameLayout frame = new FrameLayout(this);
			frame.setId(R.id.fragmentContainer);
			setContentView(frame);
		}

		@Override
		public void runOnDbThread(Runnable runnable) {
		}

		@Override
		public ActivityComponent getActivityComponent() {
			return null;
		}

		@Override
		public void showNextFragment(BaseFragment f) {
		}

		@Override
		public void handleException(Exception e) {
		}
	}

	public static class Editor extends TextEditorFragment {

		@Override
		public void injectFragment(ActivityComponent component) {
			viewModelFactory = new ViewModelProvider.Factory() {
				@NonNull
				@Override
				public <T extends ViewModel> T create(
						@NonNull Class<T> modelClass) {
					return modelClass.cast(model);
				}
			};
		}
	}

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	private VaultViewModelRig rig;
	private Host host;
	private final List<Runnable> queued = new ArrayList<>();

	@Before
	public void setUp() throws Exception {
		ShadowDialog.reset();
		rig = new VaultViewModelRig();
		model = rig.newModel();
		host = Robolectric.buildActivity(Host.class).setup().get();
		idle();
	}

	@After
	public void tearDown() {
		model = null;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private void saveInTheBackground() {
		model = new VaultViewModel(rig.app, rig.vault.manager, queued::add,
				rig.store, rig.gate);
	}

	private void runTheBackground() {
		while (!queued.isEmpty()) queued.remove(0).run();
		idle();
	}

	private VaultItem stored() throws Exception {
		return rig.vault.manager.addItem(VaultItem.ItemType.DOCUMENT,
				"notes.txt", bytes("first draft"));
	}

	private VaultItem storedWithPassword() throws Exception {
		return rig.vault.manager.addItemWithPassword(
				VaultItem.ItemType.DOCUMENT, "notes.txt",
				bytes("first draft"), "open sesame".toCharArray());
	}

	private Editor show(Editor editor) {
		host.getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragmentContainer, editor, TAG)
				.addToBackStack(TAG).commit();
		idle();
		return editor;
	}

	private Editor editing(VaultItem item) {
		Editor editor = new Editor();
		editor.setArguments(TextEditorFragment.newInstanceForEdit(item.id,
				item.name, "first draft").getArguments());
		return show(editor);
	}

	private boolean isOpen() {
		idle();
		return host.getSupportFragmentManager().findFragmentByTag(TAG)
				!= null;
	}

	private static EditText text(Editor editor) {
		return editor.requireView().findViewById(R.id.document_content_input);
	}

	private static EditText name(Editor editor) {
		return editor.requireView().findViewById(R.id.document_name_input);
	}

	private static void save(Editor editor) {
		editor.requireView().findViewById(R.id.fab_save).performClick();
		idle();
	}

	private static AlertDialog dialog() {
		idle();
		return (AlertDialog) ShadowDialog.getLatestDialog();
	}

	private static void answer(AlertDialog dialog, String first,
			String second) {
		((EditText) dialog.findViewById(R.id.password_input_1))
				.setText(first);
		if (second != null) {
			((EditText) dialog.findViewById(R.id.password_input_2))
					.setText(second);
		}
		dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		idle();
	}

	@Test
	public void theEditorClosesOnceTheChangedTextIsStored() throws Exception {
		VaultItem old = stored();
		Editor editor = editing(old);
		text(editor).setText("second draft");

		save(editor);

		assertFalse(isOpen());
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named("notes.txt");
		assertNotNull(now);
		assertArrayEquals(bytes("second draft"), rig.vault.raw(now.id));
		assertEquals(now.id, model.latestDocumentId(old.id));
	}

	@Test
	public void theEditorStaysOpenUntilTheSaveHasAResult() throws Exception {
		saveInTheBackground();
		VaultItem old = stored();
		Editor editor = editing(old);
		text(editor).setText("second draft");

		save(editor);

		assertTrue("closed before the text was stored", isOpen());
		assertFalse(text(editor).isEnabled());
		assertFalse(editor.requireView().findViewById(R.id.fab_save)
				.isEnabled());
		host.getOnBackPressedDispatcher().onBackPressed();
		assertTrue("left while the text was being stored", isOpen());
		assertNull(ShadowDialog.getLatestDialog());
		assertArrayEquals(bytes("first draft"), rig.vault.raw(old.id));

		runTheBackground();

		assertFalse(isOpen());
		VaultItem now = rig.vault.named("notes.txt");
		assertNotNull(now);
		assertArrayEquals(bytes("second draft"), rig.vault.raw(now.id));
	}

	@Test
	public void aFailedSaveKeepsTheEditorOpenWithTheTextStillUnsaved()
			throws Exception {
		VaultItem old = stored();
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addMediaItemWithPassword(any(),
						anyString(), any(), any(), any());
		Editor editor = editing(old);
		text(editor).setText("second draft");

		save(editor);

		assertTrue("the new text was thrown away", isOpen());
		assertEquals("second draft", text(editor).getText().toString());
		assertTrue(text(editor).isEnabled());
		assertTrue(editor.requireView().findViewById(R.id.fab_save)
				.isEnabled());
		assertArrayEquals(bytes("first draft"), rig.vault.raw(old.id));
		assertNull("the failure is told again on the next screen",
				model.getErrorMessage().getValue());

		host.getOnBackPressedDispatcher().onBackPressed();
		AlertDialog unsaved = dialog();
		assertNotNull("the text counts as saved", unsaved);
		assertTrue(unsaved.isShowing());
		assertTrue(isOpen());
	}

	@Test
	public void aNewDocumentThatCannotBeStoredKeepsTheEditorOpen()
			throws Exception {
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addMediaItemWithPassword(any(),
						anyString(), any(), any(), any());
		Editor editor = show(new Editor());
		name(editor).setText("fresh");
		text(editor).setText("hello");

		save(editor);

		assertTrue(isOpen());
		assertEquals("hello", text(editor).getText().toString());
		assertEquals(0, rig.vault.size());
	}

	@Test
	public void aNewDocumentClosesTheEditorOnceItIsStored() throws Exception {
		Editor editor = show(new Editor());
		name(editor).setText("fresh");
		text(editor).setText("hello");

		save(editor);

		assertFalse(isOpen());
		VaultItem now = rig.vault.named("fresh.txt");
		assertNotNull(now);
		assertArrayEquals(bytes("hello"), rig.vault.raw(now.id));
	}

	@Test
	public void aResultLeftByAnEarlierEditorDoesNotCloseTheNextOne()
			throws Exception {
		model.addDocumentWithPassword("earlier.txt", bytes("text"), null);
		idle();
		assertNotNull(model.getDocumentSaveResult().getValue());

		Editor editor = show(new Editor());

		assertTrue(isOpen());
		assertTrue(text(editor).isEnabled());
	}

	@Test
	public void aPlainSaveOfAProtectedDocumentAsksForItsPassword()
			throws Exception {
		VaultItem old = storedWithPassword();
		Editor editor = editing(old);
		text(editor).setText("second draft");

		save(editor);

		AlertDialog asked = dialog();
		assertNotNull("no password was asked for", asked);
		assertTrue(asked.isShowing());
		assertTrue(isOpen());
		verify(rig.vault.manager, never()).addMediaItemWithPassword(any(),
				anyString(), any(), any(), any());
		assertEquals(1, rig.vault.size());
		assertTrue(rig.vault.manager.itemHasExtraPassword(old.id));

		answer(asked, "open sesame", null);

		assertFalse(isOpen());
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named("notes.txt");
		assertNotNull(now);
		assertNotEquals(old.id, now.id);
		assertTrue("the document lost its password", now.hasExtraPassword);
		assertArrayEquals(bytes("second draft"),
				rig.vault.manager.getItemContentWithPassword(now.id,
						"open sesame".toCharArray()));
	}

	@Test
	public void aWrongPasswordKeepsTheEditorOpenAndAsksAgain()
			throws Exception {
		VaultItem old = storedWithPassword();
		Editor editor = editing(old);
		text(editor).setText("second draft");
		save(editor);
		AlertDialog asked = dialog();

		answer(asked, "open says me", null);

		assertTrue(isOpen());
		assertEquals("second draft", text(editor).getText().toString());
		AlertDialog again = dialog();
		assertNotSame(asked, again);
		assertTrue(again.isShowing());
		verify(rig.vault.manager, never()).addMediaItemWithPassword(any(),
				anyString(), any(), any(), any());
		assertArrayEquals(bytes("first draft"),
				rig.vault.manager.getItemContentWithPassword(old.id,
						"open sesame".toCharArray()));
	}

	@Test
	public void savingWithAPasswordWhileEditingReplacesTheOriginal()
			throws Exception {
		VaultItem old = stored();
		Editor editor = editing(old);
		text(editor).setText("second draft");

		editor.onOptionsItemSelected(
				new RoboMenuItem(R.id.action_save_with_password));
		answer(dialog(), "new secret", "new secret");

		assertFalse(isOpen());
		assertEquals("the unprotected original stays beside the copy", 1,
				rig.vault.size());
		VaultItem now = rig.vault.named("notes.txt");
		assertNotNull(now);
		assertNotEquals(old.id, now.id);
		assertTrue(now.hasExtraPassword);
		assertArrayEquals(bytes("second draft"),
				rig.vault.manager.getItemContentWithPassword(now.id,
						"new secret".toCharArray()));
	}

	@Test
	public void aSavedTextWhoseEarlierVersionStaysStillClosesTheEditor()
			throws Exception {
		VaultItem old = stored();
		rig.vault.deleteFailures.add(new java.io.IOException("busy"));
		rig.vault.deleteFailures.add(new java.io.IOException("busy"));
		Editor editor = editing(old);
		text(editor).setText("second draft");

		save(editor);

		assertFalse(isOpen());
		assertEquals(2, rig.vault.size());
		assertEquals(rig.app.getString(
				R.string.vault_msg_saved_earlier_version_kept),
				org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
	}
}
