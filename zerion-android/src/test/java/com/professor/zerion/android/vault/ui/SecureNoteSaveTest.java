package com.professor.zerion.android.vault.ui;

import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Looper;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.google.android.material.textfield.TextInputLayout;
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
import org.robolectric.fakes.RoboMenu;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SecureNoteSaveTest {

	private static final String TAG = "note";
	private static final String PROTECTED_TITLE =
			ProtectedNoteCodec.TITLE_PREFIX + "Diary";

	private static final String NOTE_PASSWORD = "open sesame";

	private static VaultViewModel model;
	private static Runnable duringTheRebuild;

	static {
		TestAndroidKeyStore.register();
	}

	public static class Host extends AppCompatActivity
			implements BaseFragment.BaseFragmentListener {

		@Override
		protected void onCreate(Bundle savedInstanceState) {
			Runnable betweenTheScreens = duringTheRebuild;
			duringTheRebuild = null;
			if (savedInstanceState != null && betweenTheScreens != null) {
				betweenTheScreens.run();
			}
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
		duringTheRebuild = null;
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
		protect(screen, password, password);
	}

	private static void protect(SecureNoteFragment screen, String password,
			String confirmation) {
		((SwitchMaterial) screen.requireView()
				.findViewById(R.id.lock_note_switch)).setChecked(true);
		((EditText) screen.requireView()
				.findViewById(R.id.note_password_input)).setText(password);
		((EditText) screen.requireView()
				.findViewById(R.id.note_password_confirm_input))
				.setText(confirmation);
	}

	private static TextInputLayout confirmation(SecureNoteFragment screen) {
		return screen.requireView()
				.findViewById(R.id.note_password_confirm_layout);
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

	private VaultItem storedProtectedNote() throws Exception {
		VaultItem note = rig.vault.manager.addItem(VaultItem.ItemType.NOTE,
				PROTECTED_TITLE, ProtectedNoteCodec.seal("dear diary",
						NOTE_PASSWORD.toCharArray()));
		model.loadVaultItems();
		runTheBackground();
		return note;
	}

	private SecureNoteFragment rebuilt() {
		controller.recreate();
		host = controller.get();
		return shown();
	}

	private Menu toolbar(SecureNoteFragment screen) {
		Menu menu = new RoboMenu(host);
		screen.onCreateOptionsMenu(menu, host.getMenuInflater());
		return menu;
	}

	private static boolean canBeTapped(Menu menu) {
		return menu.findItem(R.id.action_save_note).isEnabled();
	}

	private static void saveFromTheToolbar(SecureNoteFragment screen) {
		screen.onOptionsItemSelected(
				new RoboMenuItem(R.id.action_save_note));
		idle();
	}

	private static boolean isLocked(SecureNoteFragment screen) {
		return ((SwitchMaterial) screen.requireView()
				.findViewById(R.id.lock_note_switch)).isChecked();
	}

	private static AlertDialog askedForThePassword() {
		idle();
		AlertDialog asked = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull("no password was asked for", asked);
		assertTrue(asked.isShowing());
		return asked;
	}

	private static void answer(AlertDialog asked, String password) {
		ViewGroup custom = asked.findViewById(androidx.appcompat.R.id.custom);
		((EditText) custom.getChildAt(0)).setText(password);
		asked.getButton(DialogInterface.BUTTON_POSITIVE).performClick();
		idle();
	}

	private void assertOnlyTheStoredNote(VaultItem note, byte[] content) {
		assertEquals(1, rig.vault.size());
		assertEquals(note.id, rig.vault.list().get(0).id);
		assertArrayEquals(content, rig.vault.raw(note.id));
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

	@Test
	public void aNewNoteCanBeSavedAtOnce() throws Exception {
		SecureNoteFragment screen = newNote("Groceries", "milk");
		Menu menu = toolbar(screen);

		assertTrue(saveButton(screen).isEnabled());
		assertTrue(canBeTapped(menu));
		saveFromTheToolbar(screen);
		pass(1000);

		assertFalse(isOpen());
		assertEquals(1, rig.vault.size());
		assertArrayEquals(bytes("milk"),
				rig.vault.raw(rig.vault.named("Groceries").id));
	}

	@Test
	public void saveTappedWhileANoteStillLoadsStoresAndRemovesNothing()
			throws Exception {
		saveInTheBackground();
		VaultItem old = storedNote();
		SecureNoteFragment screen = show(old.id);
		Menu menu = toolbar(screen);
		assertEquals("the content arrived before the vault was read", 1,
				queued.size());
		assertEquals("Groceries", title(screen).getText().toString());
		assertEquals("", text(screen).getText().toString());

		save(screen);
		saveFromTheToolbar(screen);

		assertEquals("an empty note was handed over to be stored", 1,
				queued.size());
		verify(rig.vault.manager, never()).deleteItem(anyString());
		assertOnlyTheStoredNote(old, bytes("milk"));
		assertFalse("the save button can be tapped before the note is there",
				saveButton(screen).isEnabled());
		assertFalse("the toolbar can save before the note is there",
				canBeTapped(menu));

		screen.onOptionsItemSelected(
				new RoboMenuItem(R.id.action_delete_note));
		assertNotNull("the note cannot be deleted while it loads",
				ShadowDialog.getLatestDialog());
		ShadowDialog.getLatestDialog().dismiss();

		runTheBackground();

		assertEquals("milk", text(screen).getText().toString());
		assertTrue(saveButton(screen).isEnabled());
		assertTrue(canBeTapped(menu));
		assertOnlyTheStoredNote(old, bytes("milk"));

		text(screen).setText("bread");
		save(screen);
		runTheBackground();
		pass(1000);

		assertFalse(isOpen());
		assertEquals(1, rig.vault.size());
		assertArrayEquals(bytes("bread"),
				rig.vault.raw(rig.vault.named("Groceries").id));
	}

	@Test
	public void saveTappedWhileAProtectedNoteIsOpenedStoresAndRemovesNothing()
			throws Exception {
		saveInTheBackground();
		VaultItem old = storedProtectedNote();
		byte[] sealed = rig.vault.raw(old.id);
		SecureNoteFragment screen = show(old.id);
		Menu menu = toolbar(screen);
		runTheBackground();
		AlertDialog asked = askedForThePassword();

		save(screen);
		saveFromTheToolbar(screen);
		assertTrue(queued.isEmpty());

		answer(asked, NOTE_PASSWORD);
		assertEquals(1, queued.size());
		assertEquals("Diary", title(screen).getText().toString());
		assertEquals("", text(screen).getText().toString());
		assertFalse(isLocked(screen));

		save(screen);
		saveFromTheToolbar(screen);

		assertEquals("an empty note was handed over to be stored", 1,
				queued.size());
		verify(rig.vault.manager, never()).deleteItem(anyString());
		assertOnlyTheStoredNote(old, sealed);
		assertFalse("the save button can be tapped before the note opened",
				saveButton(screen).isEnabled());
		assertFalse("the toolbar can save before the note opened",
				canBeTapped(menu));

		runTheBackground();

		assertEquals("dear diary", text(screen).getText().toString());
		assertTrue(isLocked(screen));
		assertTrue(saveButton(screen).isEnabled());
		assertTrue(canBeTapped(menu));
		assertOnlyTheStoredNote(old, sealed);

		text(screen).setText("second entry");
		protect(screen, NOTE_PASSWORD);
		save(screen);
		runTheBackground();
		pass(1000);

		assertFalse(isOpen());
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named(PROTECTED_TITLE);
		assertNotNull(now);
		assertNotEquals(old.id, now.id);
		assertArrayEquals(bytes("second entry"), ProtectedNoteCodec.open(
				rig.vault.raw(now.id), NOTE_PASSWORD.toCharArray()));
	}

	@Test
	public void aNewNoteIsSealedOnlyWhenItsPasswordIsTypedTwiceAlike()
			throws Exception {
		SecureNoteFragment screen = newNote("Diary", "dear diary");
		protect(screen, NOTE_PASSWORD, "open sesane");
		assertEquals(View.VISIBLE, confirmation(screen).getVisibility());

		save(screen);
		pass(1000);

		assertTrue("the note closed after a refused save", isOpen());
		assertEquals(rig.app.getString(R.string.vault_password_mismatch),
				String.valueOf(confirmation(screen).getError()));
		verify(rig.vault.manager, never()).addItem(any(), anyString(),
				any());
		assertEquals(0, rig.vault.size());
		assertTrue(saveButton(screen).isEnabled());

		protect(screen, NOTE_PASSWORD, NOTE_PASSWORD);
		assertNull(confirmation(screen).getError());
		save(screen);
		pass(1000);

		assertFalse(isOpen());
		VaultItem now = rig.vault.named(PROTECTED_TITLE);
		assertNotNull(now);
		assertArrayEquals(bytes("dear diary"), ProtectedNoteCodec.open(
				rig.vault.raw(now.id), NOTE_PASSWORD.toCharArray()));
	}

	@Test
	public void aPlainNoteGivenAPasswordAsksForItTwice() throws Exception {
		VaultItem old = storedNote();
		SecureNoteFragment screen = show(old.id);
		assertEquals("milk", text(screen).getText().toString());
		assertEquals(View.GONE, confirmation(screen).getVisibility());

		protect(screen, NOTE_PASSWORD, "");
		save(screen);
		pass(1000);

		assertTrue(isOpen());
		assertEquals(rig.app.getString(R.string.vault_password_mismatch),
				String.valueOf(confirmation(screen).getError()));
		verify(rig.vault.manager, never()).deleteItem(anyString());
		assertOnlyTheStoredNote(old, bytes("milk"));

		protect(screen, NOTE_PASSWORD, NOTE_PASSWORD);
		save(screen);
		pass(1000);

		assertFalse(isOpen());
		VaultItem now = rig.vault.named(ProtectedNoteCodec.TITLE_PREFIX
				+ "Groceries");
		assertNotNull(now);
		assertArrayEquals(bytes("milk"), ProtectedNoteCodec.open(
				rig.vault.raw(now.id), NOTE_PASSWORD.toCharArray()));
	}

	@Test
	public void aNoteOpenedWithItsPasswordAsksForItOnce() throws Exception {
		VaultItem old = storedProtectedNote();
		SecureNoteFragment screen = show(old.id);
		answer(askedForThePassword(), NOTE_PASSWORD);
		assertTrue(isLocked(screen));
		assertEquals(View.GONE, confirmation(screen).getVisibility());

		text(screen).setText("second entry");
		protect(screen, NOTE_PASSWORD, "");
		save(screen);
		pass(1000);

		assertFalse(isOpen());
		VaultItem now = rig.vault.named(PROTECTED_TITLE);
		assertNotNull(now);
		assertArrayEquals(bytes("second entry"), ProtectedNoteCodec.open(
				rig.vault.raw(now.id), NOTE_PASSWORD.toCharArray()));
	}

	@Test
	public void aTypoInThePasswordOfAnOpenedNoteStoresAndRemovesNothing()
			throws Exception {
		VaultItem old = storedProtectedNote();
		byte[] sealed = rig.vault.raw(old.id);
		SecureNoteFragment screen = show(old.id);
		answer(askedForThePassword(), NOTE_PASSWORD);
		assertEquals("dear diary", text(screen).getText().toString());
		TextInputLayout passwordLayout = screen.requireView()
				.findViewById(R.id.note_password_layout);

		text(screen).setText("second entry");
		protect(screen, "open sesane");
		save(screen);
		pass(1000);

		assertTrue("the note closed after a refused save", isOpen());
		verify(rig.vault.manager, never()).deleteItem(anyString());
		assertOnlyTheStoredNote(old, sealed);
		assertEquals(rig.app.getString(R.string.vault_incorrect_password),
				String.valueOf(passwordLayout.getError()));
		assertEquals("second entry", text(screen).getText().toString());
		assertTrue(saveButton(screen).isEnabled());

		protect(screen, NOTE_PASSWORD);
		assertNull(passwordLayout.getError());
		save(screen);
		pass(1000);

		assertFalse(isOpen());
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named(PROTECTED_TITLE);
		assertNotNull(now);
		assertNotEquals(old.id, now.id);
		assertArrayEquals(bytes("second entry"), ProtectedNoteCodec.open(
				rig.vault.raw(now.id), NOTE_PASSWORD.toCharArray()));
	}

	@Test
	public void aStoredPlainNoteUnderTheLockPrefixOpensWithoutAPassword()
			throws Exception {
		VaultItem old = rig.vault.manager.addItem(VaultItem.ItemType.NOTE,
				PROTECTED_TITLE, bytes("milk"));
		model.loadVaultItems();
		runTheBackground();

		SecureNoteFragment screen = show(old.id);

		assertNull("a password was asked for a note that has none",
				ShadowDialog.getLatestDialog());
		assertEquals("Diary", title(screen).getText().toString());
		assertEquals("milk", text(screen).getText().toString());
		assertFalse(isLocked(screen));

		text(screen).setText("bread");
		save(screen);
		pass(1000);

		assertFalse(isOpen());
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named("Diary");
		assertNotNull(now);
		assertArrayEquals(bytes("bread"), rig.vault.raw(now.id));
	}

	@Test
	public void aWrongNotePasswordLeavesTheNoteUntouchedAndSaveRefused()
			throws Exception {
		saveInTheBackground();
		VaultItem old = storedProtectedNote();
		byte[] sealed = rig.vault.raw(old.id);
		SecureNoteFragment screen = show(old.id);
		Menu menu = toolbar(screen);
		runTheBackground();
		AlertDialog asked = askedForThePassword();

		answer(asked, "open says me");
		runTheBackground();

		AlertDialog again = askedForThePassword();
		assertNotEquals("the password was not asked for again", asked,
				again);
		assertEquals("", text(screen).getText().toString());
		save(screen);
		saveFromTheToolbar(screen);

		assertTrue("an empty note was handed over to be stored",
				queued.isEmpty());
		assertFalse(saveButton(screen).isEnabled());
		assertFalse(canBeTapped(menu));
		verify(rig.vault.manager, never()).deleteItem(anyString());
		assertOnlyTheStoredNote(old, sealed);
		assertArrayEquals(bytes("dear diary"), ProtectedNoteCodec.open(
				rig.vault.raw(old.id), NOTE_PASSWORD.toCharArray()));
	}

	@Test
	public void aScreenRebuiltDuringASaveThatFailsGetsItsTextBack()
			throws Exception {
		saveInTheBackground();
		VaultItem old = storedNote();
		SecureNoteFragment first = show(old.id);
		runTheBackground();
		title(first).setText("Shopping");
		text(first).setText("bread");
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addItem(any(), anyString(), any());
		save(first);
		assertEquals(1, queued.size());

		SecureNoteFragment screen = rebuilt();
		assertNotNull(screen);
		assertFalse(saveButton(screen).isEnabled());
		assertFalse(text(screen).isEnabled());
		save(screen);
		assertEquals(1, queued.size());

		runTheBackground();
		pass(1000);

		assertTrue("the typed text was thrown away", isOpen());
		assertEquals("Shopping", title(screen).getText().toString());
		assertEquals("bread", text(screen).getText().toString());
		assertTrue(text(screen).isEnabled());
		assertTrue(saveButton(screen).isEnabled());
		assertOnlyTheStoredNote(old, bytes("milk"));

		back();

		AlertDialog unsaved = (AlertDialog) ShadowDialog.getLatestDialog();
		assertNotNull("the text counts as saved", unsaved);
		assertTrue(unsaved.isShowing());
		assertTrue(isOpen());
	}

	@Test
	public void aProtectedNoteRebuiltDuringAFailedSaveStaysProtected()
			throws Exception {
		saveInTheBackground();
		SecureNoteFragment first = newNote("Diary", "dear diary");
		protect(first, NOTE_PASSWORD);
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addItem(any(), anyString(), any());
		save(first);

		SecureNoteFragment screen = rebuilt();
		runTheBackground();
		pass(1000);

		assertTrue(isOpen());
		assertEquals("Diary", title(screen).getText().toString());
		assertEquals("dear diary", text(screen).getText().toString());
		assertTrue("the note would now be stored without its password",
				isLocked(screen));

		save(screen);

		assertTrue("the note was handed over without a password",
				queued.isEmpty());
		assertEquals(0, rig.vault.size());
	}

	@Test
	public void aSaveThatFailsWhileTheScreenIsRebuiltIsNotMissed()
			throws Exception {
		saveInTheBackground();
		SecureNoteFragment first = newNote("Groceries", "milk");
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addItem(any(), anyString(), any());
		save(first);
		duringTheRebuild = () -> queued.remove(0).run();

		SecureNoteFragment screen = rebuilt();
		pass(1000);

		assertNull("the save did not finish between the two screens",
				duringTheRebuild);
		assertTrue(queued.isEmpty());
		assertTrue(isOpen());
		assertEquals("Groceries", title(screen).getText().toString());
		assertEquals("milk", text(screen).getText().toString());
		assertTrue(saveButton(screen).isEnabled());
		back();
		assertNotNull("the text counts as saved",
				ShadowDialog.getLatestDialog());
	}

	@Test
	public void aSaveStoredWhileTheScreenIsRebuiltStillClosesIt()
			throws Exception {
		saveInTheBackground();
		VaultItem old = storedNote();
		SecureNoteFragment first = show(old.id);
		runTheBackground();
		text(first).setText("bread");
		save(first);
		duringTheRebuild = () -> queued.remove(0).run();

		rebuilt();
		pass(1000);

		assertNull("the save did not finish between the two screens",
				duringTheRebuild);
		assertFalse("the screen stayed open on a note that is stored",
				isOpen());
		assertEquals(1, rig.vault.size());
		assertArrayEquals(bytes("bread"),
				rig.vault.raw(rig.vault.named("Groceries").id));
	}

	@Test
	public void aScreenRebuiltWhileItClosesAfterASaveStillCloses()
			throws Exception {
		SecureNoteFragment first = newNote("Groceries", "milk");
		long request = model.newNoteSaveRequest() + 1;
		saveButton(first).performClick();
		for (int task = 0; task < 50 && model.isNoteSavePending(request);
				task++) {
			shadowOf(Looper.getMainLooper()).runOneTask();
		}
		assertFalse("the screen never took the result of its save",
				model.isNoteSavePending(request));
		assertEquals(1, rig.vault.size());
		assertNotNull("the screen closed before it could be rebuilt",
				host.getSupportFragmentManager().findFragmentByTag(TAG));

		controller.recreate();
		host = controller.get();
		pass(1000);

		assertFalse("the screen stayed open on a note that is stored",
				isOpen());
		assertEquals(1, rig.vault.size());
	}

	@Test
	public void aRebuiltScreenWithoutItsTextShowsTheStoredNoteAgain()
			throws Exception {
		saveInTheBackground();
		VaultItem old = storedNote();
		SecureNoteFragment first = show(old.id);
		runTheBackground();
		text(first).setText("bread");
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addItem(any(), anyString(), any());
		save(first);
		SecureNoteFragment screen = rebuilt();

		model.clearSensitiveMemory();
		model.loadVaultItems();
		runTheBackground();
		assertFalse("an editor without the text of the note can save",
				saveButton(screen).isEnabled());
		save(screen);
		runTheBackground();
		pass(1000);

		assertTrue(isOpen());
		assertEquals("Groceries", title(screen).getText().toString());
		assertEquals("milk", text(screen).getText().toString());
		assertTrue(saveButton(screen).isEnabled());
		assertOnlyTheStoredNote(old, bytes("milk"));

		back();

		assertNull("a note nobody changed asks before leaving",
				ShadowDialog.getLatestDialog());
		assertFalse(isOpen());
	}
}
