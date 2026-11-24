package com.professor.zerion.android.vault.ui;

import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.crypto.ProtectedNoteCodec;
import com.professor.zerion.android.vault.model.VaultItem;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.fakes.RoboMenuItem;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SecureNoteSaveTest {

	private static final String TAG = "note";
	private static final String PROTECTED_TITLE =
			ProtectedNoteCodec.TITLE_PREFIX + "Diary";

	private static VaultViewModel model;

	static {
		TestAndroidKeyStore.register();
	}

	public static class Host extends AppCompatActivity
			implements BaseFragment.BaseFragmentListener {

		@Override
		protected void onCreate(Bundle savedInstanceState) {
			setTheme(R.style.ZerionTheme_NoActionBar);
			new ViewModelProvider(this, new ViewModelProvider.Factory() {
				@NonNull
				@Override
				public <T extends ViewModel> T create(
						@NonNull Class<T> modelClass) {
					return modelClass.cast(model);
				}
			}).get(VaultViewModel.class);
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

	private VaultViewModelRig rig;
	private ActivityController<Host> controller;
	private Host host;
	private final List<Runnable> queued = new ArrayList<>();

	@Before
	public void setUp() throws Exception {
		ShadowDialog.reset();
		ShadowToast.reset();
		rig = new VaultViewModelRig();
		model = rig.newModel();
	}

	@After
	public void tearDown() {
		model = null;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void pass(long ms) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
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

	private SecureNoteFragment show(String noteId) {
		controller = Robolectric.buildActivity(Host.class).setup();
		host = controller.get();
		idle();
		SecureNoteFragment screen = SecureNoteFragment.newInstance(noteId);
		host.getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragmentContainer, screen, TAG)
				.addToBackStack(TAG).commit();
		idle();
		return screen;
	}

	private SecureNoteFragment shown() {
		idle();
		return (SecureNoteFragment) host.getSupportFragmentManager()
				.findFragmentByTag(TAG);
	}

	private boolean isOpen() {
		return shown() != null;
	}

	private static EditText title(SecureNoteFragment screen) {
		return screen.requireView().findViewById(R.id.note_title_input);
	}

	private static EditText text(SecureNoteFragment screen) {
		return screen.requireView().findViewById(R.id.note_content_input);
	}

	private static View saveButton(SecureNoteFragment screen) {
		return screen.requireView().findViewById(R.id.save_note_fab);
	}

	private static void protect(SecureNoteFragment screen, String password) {
		((SwitchMaterial) screen.requireView()
				.findViewById(R.id.lock_note_switch)).setChecked(true);
		((EditText) screen.requireView()
				.findViewById(R.id.note_password_input)).setText(password);
	}

	private static void save(SecureNoteFragment screen) {
		saveButton(screen).performClick();
		idle();
	}

	private void back() {
		host.getOnBackPressedDispatcher().onBackPressed();
		idle();
	}

	private VaultItem storedNote() throws Exception {
		VaultItem note = rig.vault.manager.addItem(VaultItem.ItemType.NOTE,
				"Groceries", bytes("milk"));
		model.loadVaultItems();
		runTheBackground();
		return note;
	}

	private SecureNoteFragment newNote(String title, String text) {
		SecureNoteFragment screen = show(null);
		title(screen).setText(title);
		text(screen).setText(text);
		return screen;
	}

	@Test
	public void theScreenClosesOnceTheNoteIsStored() throws Exception {
		SecureNoteFragment screen = newNote("Groceries", "milk");

		save(screen);
		pass(1000);

		assertFalse(isOpen());
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named("Groceries");
		assertNotNull(now);
		assertArrayEquals(bytes("milk"), rig.vault.raw(now.id));
	}

	@Test
	public void aSlowSaveHoldsTheScreenUntilItHasAResult() throws Exception {
		saveInTheBackground();
		SecureNoteFragment screen = newNote("Groceries", "milk");

		save(screen);
		pass(5000);

		assertTrue("closed before the note was stored", isOpen());
		assertEquals(1, queued.size());
		assertFalse("the save button was given back while the save runs",
				saveButton(screen).isEnabled());
		assertFalse(title(screen).isEnabled());
		assertFalse(text(screen).isEnabled());
		assertFalse(screen.requireView().findViewById(R.id.lock_note_switch)
				.isEnabled());

		save(screen);
		screen.onOptionsItemSelected(
				new RoboMenuItem(R.id.action_save_note));
		screen.onOptionsItemSelected(
				new RoboMenuItem(R.id.action_delete_note));
		idle();
		assertEquals("a second tap started a second save", 1, queued.size());
		assertNull(ShadowDialog.getLatestDialog());

		back();
		assertTrue("left while the note was being stored", isOpen());
		assertNull(ShadowDialog.getLatestDialog());
		assertTrue(screen.onBackPressed());
		assertEquals(0, rig.vault.size());

		runTheBackground();
		pass(1000);

		assertFalse(isOpen());
		assertEquals("the note was stored more than once", 1,
				rig.vault.size());
		assertArrayEquals(bytes("milk"),
				rig.vault.raw(rig.vault.named("Groceries").id));
	}

	@Test
	public void aSecondTapDuringASlowSaveKeepsThePasswordOfTheFirst()
			throws Exception {
		saveInTheBackground();
		SecureNoteFragment screen = newNote("Diary", "dear diary");
		protect(screen, "open sesame");

		save(screen);
		pass(5000);
		save(screen);
		assertEquals(1, queued.size());

		runTheBackground();
		pass(1000);

		assertFalse(isOpen());
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named(PROTECTED_TITLE);
		assertNotNull(now);
		byte[] opened = ProtectedNoteCodec.open(rig.vault.raw(now.id),
				"open sesame".toCharArray());
		assertNotNull("the note does not open with the typed password",
				opened);
		assertArrayEquals(bytes("dear diary"), opened);
	}

	@Test
	public void aFailedSaveKeepsTheTextAndAsksBeforeLeaving()
			throws Exception {
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addItem(any(), anyString(), any());
		SecureNoteFragment screen = newNote("Groceries", "milk");

		save(screen);
		pass(5000);

		assertTrue("the typed text was thrown away", isOpen());
		assertEquals("Groceries", title(screen).getText().toString());
		assertEquals("milk", text(screen).getText().toString());
		assertTrue(title(screen).isEnabled());
		assertTrue(text(screen).isEnabled());
		assertTrue(saveButton(screen).isEnabled());
		assertEquals(0, rig.vault.size());
		assertNull("the failure is told again on the next screen",
				model.getErrorMessage().getValue());

		back();

		AlertDialog unsaved = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull("the text counts as saved", unsaved);
		assertTrue(unsaved.isShowing());
		assertTrue(isOpen());
	}

	@Test
	public void aSlowSaveThatFailsStillAsksBeforeLeaving() throws Exception {
		saveInTheBackground();
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addItem(any(), anyString(), any());
		SecureNoteFragment screen = newNote("Groceries", "milk");

		save(screen);
		pass(5000);
		runTheBackground();
		pass(1000);

		assertTrue(isOpen());
		assertEquals("milk", text(screen).getText().toString());
		assertTrue(saveButton(screen).isEnabled());
		back();
		AlertDialog unsaved = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull("the text counts as saved", unsaved);
		assertTrue(unsaved.isShowing());
		assertTrue(isOpen());
	}

	@Test
	public void aSavedNoteWhoseEarlierVersionStaysStillClosesTheScreen()
			throws Exception {
		VaultItem old = storedNote();
		rig.vault.deleteFailures.add(new java.io.IOException("busy"));
		rig.vault.deleteFailures.add(new java.io.IOException("busy"));
		SecureNoteFragment screen = show(old.id);
		assertEquals("milk", text(screen).getText().toString());
		text(screen).setText("bread");

		save(screen);
		pass(1000);

		assertFalse("a second save would add another copy", isOpen());
		assertEquals(2, rig.vault.size());
		assertArrayEquals(bytes("milk"), rig.vault.raw(old.id));
		assertEquals(rig.app.getString(
				R.string.vault_msg_saved_earlier_version_kept),
				ShadowToast.getTextOfLatestToast());
	}

	@Test
	public void aChangedNoteReplacesItsEarlierVersion() throws Exception {
		VaultItem old = storedNote();
		SecureNoteFragment screen = show(old.id);
		text(screen).setText("bread");

		save(screen);
		pass(1000);

		assertFalse(isOpen());
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named("Groceries");
		assertNotNull(now);
		assertNotEquals(old.id, now.id);
		assertArrayEquals(bytes("bread"), rig.vault.raw(now.id));
	}

	@Test
	public void aResultLeftByAnEarlierScreenDoesNotCloseTheNextOne()
			throws Exception {
		model.saveNote(model.newNoteSaveRequest(), "Earlier", "text", null);
		idle();
		assertNotNull(model.getNoteSaveResult().getValue());

		SecureNoteFragment screen = newNote("Groceries", "milk");
		pass(1000);

		assertTrue(isOpen());
		assertTrue(text(screen).isEnabled());
		assertTrue(saveButton(screen).isEnabled());
	}

	@Test
	public void theResultOfAnEarlierSaveDoesNotEndALaterOne()
			throws Exception {
		saveInTheBackground();
		model.saveNote(model.newNoteSaveRequest(), "Earlier", "text", null);
		SecureNoteFragment screen = newNote("Groceries", "milk");
		save(screen);
		assertEquals(2, queued.size());

		queued.remove(0).run();
		idle();
		pass(1000);

		assertTrue("closed on the result of another save", isOpen());
		assertFalse(saveButton(screen).isEnabled());

		runTheBackground();
		pass(1000);
		assertFalse(isOpen());
		assertEquals(2, rig.vault.size());
	}

	@Test
	public void aScreenRebuiltDuringASaveStaysHeldUntilTheResult()
			throws Exception {
		saveInTheBackground();
		SecureNoteFragment first = newNote("Groceries", "milk");
		save(first);

		controller.recreate();
		host = controller.get();
		SecureNoteFragment screen = shown();

		assertNotNull(screen);
		assertFalse(saveButton(screen).isEnabled());
		assertFalse(text(screen).isEnabled());
		save(screen);
		assertEquals(1, queued.size());

		runTheBackground();
		pass(1000);

		assertFalse(isOpen());
		assertEquals(1, rig.vault.size());
	}
}
