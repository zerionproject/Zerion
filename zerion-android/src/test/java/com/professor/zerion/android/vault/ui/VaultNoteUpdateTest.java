package com.professor.zerion.android.vault.ui;

import android.os.Looper;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.model.VaultItem;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.vault.ui.VaultViewModelRig.await;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultNoteUpdateTest {

	private static final String PROTECTED_TITLE = "🔒 Diary";

	static {
		TestAndroidKeyStore.register();
	}

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static VaultItem plainNote(VaultViewModelRig rig, VaultViewModel vm)
			throws Exception {
		vm.saveNote(vm.newNoteSaveRequest(), "Groceries", "milk", null);
		idle();
		VaultItem note = rig.vault.named("Groceries");
		assertNotNull(note);
		vm.clearMessages();
		clearInvocations(rig.vault.manager);
		return note;
	}

	private static VaultItem protectedNote(VaultViewModelRig rig,
			VaultViewModel vm) throws Exception {
		vm.saveNoteWithPassword(vm.newNoteSaveRequest(), "Diary",
				"dear diary", "pw".toCharArray(),
				null);
		idle();
		VaultItem note = rig.vault.named(PROTECTED_TITLE);
		assertNotNull(note);
		vm.clearMessages();
		clearInvocations(rig.vault.manager);
		return note;
	}

	private static String opened(VaultViewModel vm, String id, String password)
			throws Exception {
		LiveData<String> text = vm.loadPasswordProtectedNote(id,
				password.toCharArray());
		await(() -> text.getValue() != null);
		return text.getValue();
	}

	@Test
	public void aNoteIsReplacedOnlyAfterItsNewVersionIsStored()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		VaultItem old = plainNote(rig, vm);

		vm.saveNote(vm.newNoteSaveRequest(), "Groceries", "bread",
				old.id);
		idle();

		InOrder order = inOrder(rig.vault.manager);
		order.verify(rig.vault.manager).addItem(eq(VaultItem.ItemType.NOTE),
				eq("Groceries"), any());
		order.verify(rig.vault.manager).deleteItem(old.id);
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named("Groceries");
		assertNotNull(now);
		assertNotEquals(old.id, now.id);
		assertArrayEquals(bytes("bread"), rig.vault.raw(now.id));
		assertEquals(rig.app.getString(R.string.vault_msg_saved),
				vm.getSuccessMessage().getValue());
		assertNull(vm.getErrorMessage().getValue());
	}

	@Test
	public void aNoteThatCannotBeStoredLeavesTheOldNoteInPlace()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		VaultItem old = plainNote(rig, vm);
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addItem(any(), anyString(), any());

		vm.saveNote(vm.newNoteSaveRequest(), "Groceries", "bread",
				old.id);
		idle();

		verify(rig.vault.manager, never()).deleteItem(anyString());
		assertEquals(1, rig.vault.size());
		assertArrayEquals(bytes("milk"), rig.vault.raw(old.id));
		assertEquals(rig.app.getString(R.string.vault_msg_storage_error),
				vm.getErrorMessage().getValue());
		assertNull(vm.getSuccessMessage().getValue());
		assertEquals(Boolean.FALSE, vm.getIsLoading().getValue());
	}

	@Test
	public void aNoteWhoseEarlierVersionStaysSaysExactlyThat()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		VaultItem old = plainNote(rig, vm);
		rig.vault.deleteFailures.add(new java.io.IOException("busy"));
		rig.vault.deleteFailures.add(new java.io.IOException("busy"));

		vm.saveNote(vm.newNoteSaveRequest(), "Groceries", "bread",
				old.id);
		idle();

		verify(rig.vault.manager, times(2)).deleteItem(old.id);
		assertEquals(2, rig.vault.size());
		assertArrayEquals(bytes("milk"), rig.vault.raw(old.id));
		assertEquals(rig.app.getString(
				R.string.vault_msg_saved_earlier_version_kept),
				vm.getErrorMessage().getValue());
		assertNull(vm.getSuccessMessage().getValue());
		assertEquals(Boolean.FALSE, vm.getIsLoading().getValue());
	}

	@Test
	public void aNoteRemovalThatFailsOnceIsTriedAgain() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		VaultItem old = plainNote(rig, vm);
		rig.vault.deleteFailures.add(new java.io.IOException("busy"));

		vm.saveNote(vm.newNoteSaveRequest(), "Groceries", "bread",
				old.id);
		idle();

		verify(rig.vault.manager, times(2)).deleteItem(old.id);
		assertEquals(1, rig.vault.size());
		assertEquals(rig.app.getString(R.string.vault_msg_saved),
				vm.getSuccessMessage().getValue());
		assertNull(vm.getErrorMessage().getValue());
	}

	@Test
	public void aProtectedNoteIsReplacedOnlyAfterItsNewVersionIsStored()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		VaultItem old = protectedNote(rig, vm);
		char[] typed = "pw".toCharArray();

		vm.saveNoteWithPassword(vm.newNoteSaveRequest(), "Diary",
				"second entry", typed, old.id);
		idle();

		InOrder order = inOrder(rig.vault.manager);
		order.verify(rig.vault.manager).addItem(eq(VaultItem.ItemType.NOTE),
				eq(PROTECTED_TITLE), any());
		order.verify(rig.vault.manager).deleteItem(old.id);
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named(PROTECTED_TITLE);
		assertNotNull(now);
		assertNotEquals(old.id, now.id);
		assertEquals(0x02, rig.vault.raw(now.id)[0]);
		assertEquals("second entry", opened(vm, now.id, "pw"));
		assertArrayEquals(new char[typed.length], typed);
		assertEquals(rig.app.getString(R.string.vault_msg_saved),
				vm.getSuccessMessage().getValue());
	}

	@Test
	public void aProtectedNoteThatCannotBeStoredLeavesTheOldNoteInPlace()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		VaultItem old = protectedNote(rig, vm);
		byte[] before = rig.vault.raw(old.id);
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addItem(any(), anyString(), any());

		vm.saveNoteWithPassword(vm.newNoteSaveRequest(), "Diary",
				"second entry", "pw".toCharArray(),
				old.id);
		idle();

		verify(rig.vault.manager, never()).deleteItem(anyString());
		assertEquals(1, rig.vault.size());
		assertArrayEquals(before, rig.vault.raw(old.id));
		assertEquals("dear diary", opened(vm, old.id, "pw"));
		assertEquals(rig.app.getString(R.string.vault_msg_save_note_failed),
				vm.getErrorMessage().getValue());
		assertNull(vm.getSuccessMessage().getValue());
	}

	@Test
	public void aProtectedNoteWhoseEarlierVersionStaysSaysExactlyThat()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		VaultItem old = protectedNote(rig, vm);
		rig.vault.deleteFailures.add(new java.io.IOException("busy"));
		rig.vault.deleteFailures.add(new SecurityException("locked"));

		vm.saveNoteWithPassword(vm.newNoteSaveRequest(), "Diary",
				"second entry", "pw".toCharArray(),
				old.id);
		idle();

		verify(rig.vault.manager, times(2)).deleteItem(old.id);
		assertEquals(2, rig.vault.size());
		assertEquals("dear diary", opened(vm, old.id, "pw"));
		assertEquals(rig.app.getString(
				R.string.vault_msg_saved_earlier_version_kept),
				vm.getErrorMessage().getValue());
		assertNull(vm.getSuccessMessage().getValue());
	}

	@Test
	public void aProtectedNoteIsNotReplacedUnderAnotherPassword()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		VaultItem old = protectedNote(rig, vm);
		byte[] before = rig.vault.raw(old.id);
		char[] typed = "pv".toCharArray();

		vm.saveNoteWithPassword(vm.newNoteSaveRequest(), "Diary",
				"second entry", typed, old.id);
		idle();

		NoteSaveResult result = result(vm);
		assertEquals(NoteSaveResult.Outcome.WRONG_PASSWORD, result.outcome);
		assertFalse(result.isStored());
		assertEquals(rig.app.getString(R.string.vault_incorrect_password),
				result.message);
		verify(rig.vault.manager, never()).addItem(any(), anyString(),
				any());
		verify(rig.vault.manager, never()).deleteItem(anyString());
		assertEquals(1, rig.vault.size());
		assertArrayEquals(before, rig.vault.raw(old.id));
		assertEquals("dear diary", opened(vm, old.id, "pw"));
		assertArrayEquals(new char[typed.length], typed);
		assertNull(vm.getSuccessMessage().getValue());
	}

	@Test
	public void aPlainNoteGivenAPasswordIsSealedUnderIt() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		VaultItem old = plainNote(rig, vm);

		vm.saveNoteWithPassword(vm.newNoteSaveRequest(), "Groceries",
				"bread", "pw".toCharArray(), old.id);
		idle();

		assertEquals(NoteSaveResult.Outcome.SAVED, result(vm).outcome);
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named("🔒 Groceries");
		assertNotNull(now);
		assertEquals("bread", opened(vm, now.id, "pw"));
	}

	private static String loaded(VaultViewModel vm, String id)
			throws Exception {
		LiveData<String> text = vm.loadNoteContent(id);
		await(() -> text.getValue() != null);
		return text.getValue();
	}

	@Test
	public void aPlainNoteIsNeverStoredUnderTheLockPrefix() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String text = "milk, bread and everything else for a long week ahead";
		assertTrue(bytes(text).length > 44);

		vm.saveNote(vm.newNoteSaveRequest(), "🔒 Groceries", text, null);
		idle();
		vm.saveNote(vm.newNoteSaveRequest(), "🔒 🔒 Twice", "x", null);
		idle();

		assertEquals(NoteSaveResult.Outcome.SAVED, result(vm).outcome);
		assertNull(rig.vault.named("🔒 Groceries"));
		VaultItem stored = rig.vault.named("Groceries");
		assertNotNull("the note was not stored under its plain title",
				stored);
		assertArrayEquals(bytes(text), rig.vault.raw(stored.id));
		assertEquals(text, loaded(vm, stored.id));
		assertNotNull(rig.vault.named("Twice"));
		assertEquals(2, rig.vault.size());
	}

	@Test
	public void aProtectedNoteIsStoredUnderOneLockPrefix() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();

		vm.saveNoteWithPassword(vm.newNoteSaveRequest(), "🔒 🔒 Diary",
				"dear diary", "pw".toCharArray(), null);
		idle();

		assertEquals(NoteSaveResult.Outcome.SAVED, result(vm).outcome);
		assertEquals(1, rig.vault.size());
		VaultItem stored = rig.vault.named(PROTECTED_TITLE);
		assertNotNull("the note was not stored under one lock prefix",
				stored);
		assertEquals("dear diary", opened(vm, stored.id, "pw"));
	}

	@Test
	public void aPlainNoteWhoseTitleIsOnlyTheLockPrefixIsRefused()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();

		vm.saveNote(vm.newNoteSaveRequest(), "🔒 🔒  ", "milk", null);
		idle();

		NoteSaveResult result = result(vm);
		assertEquals(NoteSaveResult.Outcome.FAILED, result.outcome);
		assertEquals(rig.app.getString(R.string.vault_msg_note_title_empty),
				result.message);
		assertEquals(0, rig.vault.size());
	}

	@Test
	public void aStoredPlainNoteUnderTheLockPrefixOpensAsText()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		String longText = "milk, bread and everything else for a long week "
				+ "ahead, with an accent é and a sign €";
		assertTrue(bytes(longText).length > 61);
		VaultItem shortNote = rig.vault.manager.addItem(
				VaultItem.ItemType.NOTE, "🔒 Short", bytes("milk"));
		VaultItem longNote = rig.vault.manager.addItem(
				VaultItem.ItemType.NOTE, "🔒 Long", bytes(longText));
		VaultItem empty = rig.vault.manager.addItem(
				VaultItem.ItemType.NOTE, "🔒 Empty", new byte[0]);
		VaultItem sealed = protectedNote(rig, vm);

		assertEquals("milk", loaded(vm, shortNote.id));
		assertEquals(longText, loaded(vm, longNote.id));
		assertEquals("", loaded(vm, empty.id));
		assertEquals("__PASSWORD_REQUIRED__", loaded(vm, sealed.id));

		vm.saveNote(vm.newNoteSaveRequest(), "Long", longText + "!",
				longNote.id);
		idle();

		assertEquals(NoteSaveResult.Outcome.SAVED, result(vm).outcome);
		assertNull(rig.vault.named("🔒 Long"));
		VaultItem now = rig.vault.named("Long");
		assertNotNull(now);
		assertEquals(longText + "!", loaded(vm, now.id));
	}

	@Test
	public void aStoredPlainNoteUnderTheLockPrefixCanBeGivenAPassword()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		VaultItem old = rig.vault.manager.addItem(VaultItem.ItemType.NOTE,
				"🔒 Short", bytes("milk"));

		vm.saveNoteWithPassword(vm.newNoteSaveRequest(), "Short", "bread",
				"pw".toCharArray(), old.id);
		idle();

		assertEquals(NoteSaveResult.Outcome.SAVED, result(vm).outcome);
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named("🔒 Short");
		assertNotNull(now);
		assertNotEquals(old.id, now.id);
		assertEquals("bread", opened(vm, now.id, "pw"));
	}

	@Test
	public void aNoteSaveThatRunsOutOfMemoryStillEndsInAResult()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		VaultItem old = plainNote(rig, vm);
		doThrow(new OutOfMemoryError("injected"))
				.when(rig.vault.manager).addItem(any(), anyString(), any());
		long request = vm.newNoteSaveRequest();

		vm.saveNote(request, "Groceries", "bread", old.id);
		idle();

		NoteSaveResult result = result(vm);
		assertEquals(request, result.request);
		assertEquals(NoteSaveResult.Outcome.FAILED, result.outcome);
		assertEquals(rig.app.getString(R.string.vault_msg_save_note_failed),
				result.message);
		assertFalse(vm.isNoteSaveRunning(request));
		assertEquals(Boolean.FALSE, vm.getIsLoading().getValue());
		verify(rig.vault.manager, never()).deleteItem(anyString());
		assertArrayEquals(bytes("milk"), rig.vault.raw(old.id));
	}

	private static NoteSaveResult result(VaultViewModel vm) {
		Event<NoteSaveResult> latest = vm.getNoteSaveResult().getValue();
		assertNotNull("the save ended without a result", latest);
		NoteSaveResult result = latest.getIfNotHandled();
		assertNotNull("the result was already taken", result);
		return result;
	}

	@Test
	public void everyNoteSaveEndsInAResultForItsOwnRequest()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();

		long first = vm.newNoteSaveRequest();
		vm.saveNote(first, "Groceries", "milk", null);
		idle();
		NoteSaveResult stored = result(vm);
		assertEquals(first, stored.request);
		assertEquals(NoteSaveResult.Outcome.SAVED, stored.outcome);
		assertTrue(stored.isStored());
		assertEquals(rig.app.getString(R.string.vault_msg_saved),
				stored.message);

		VaultItem old = rig.vault.named("Groceries");
		assertNotNull(old);
		rig.vault.deleteFailures.add(new java.io.IOException("busy"));
		rig.vault.deleteFailures.add(new java.io.IOException("busy"));
		long second = vm.newNoteSaveRequest();
		assertNotEquals(first, second);
		vm.saveNote(second, "Groceries", "bread", old.id);
		idle();
		NoteSaveResult kept = result(vm);
		assertEquals(second, kept.request);
		assertEquals(NoteSaveResult.Outcome.SAVED_EARLIER_VERSION_KEPT,
				kept.outcome);
		assertTrue(kept.isStored());
		assertEquals(rig.app.getString(
				R.string.vault_msg_saved_earlier_version_kept),
				kept.message);

		long third = vm.newNoteSaveRequest();
		vm.saveNote(third, " ", "eggs", null);
		idle();
		NoteSaveResult untitled = result(vm);
		assertEquals(third, untitled.request);
		assertEquals(NoteSaveResult.Outcome.FAILED, untitled.outcome);
		assertEquals(rig.app.getString(R.string.vault_msg_note_title_empty),
				untitled.message);

		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addItem(any(), anyString(), any());
		long fourth = vm.newNoteSaveRequest();
		vm.saveNote(fourth, "Groceries", "eggs", null);
		idle();
		NoteSaveResult failed = result(vm);
		assertEquals(fourth, failed.request);
		assertEquals(NoteSaveResult.Outcome.FAILED, failed.outcome);
		assertFalse(failed.isStored());
		assertEquals(rig.app.getString(R.string.vault_msg_storage_error),
				failed.message);
		assertEquals(2, rig.vault.size());
	}

	@Test
	public void aNoteSaveCountsAsRunningUntilItsResultIsOut()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		java.util.List<Runnable> queued = new java.util.ArrayList<>();
		VaultViewModel vm = new VaultViewModel(rig.app, rig.vault.manager,
				queued::add, rig.store, rig.gate);
		long request = vm.newNoteSaveRequest();
		assertFalse(vm.isNoteSaveRunning(request));

		vm.saveNote(request, "Groceries", "milk", null);

		assertTrue(vm.isNoteSaveRunning(request));
		assertNull(vm.getNoteSaveResult().getValue());
		assertEquals(0, rig.vault.size());
		while (!queued.isEmpty()) queued.remove(0).run();
		idle();
		assertFalse(vm.isNoteSaveRunning(request));
		assertEquals(request, result(vm).request);
		assertEquals(1, rig.vault.size());
	}

	private static VaultViewModel.UnsavedNoteReceiver into(
			List<String> handedBack, List<char[]> arrays) {
		return (title, content, passwordProtected) -> {
			handedBack.add(new String(title) + " / " + new String(content)
					+ (passwordProtected ? " / protected" : " / plain"));
			arrays.add(title);
			arrays.add(content);
		};
	}

	@Test
	public void theTextOfAFailedSaveIsHandedBackOnceAndThenWiped()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addItem(any(), anyString(), any());
		List<String> handedBack = new ArrayList<>();
		List<char[]> arrays = new ArrayList<>();
		long request = vm.newNoteSaveRequest();

		vm.saveNote(request, "Groceries", "milk", null);
		idle();

		assertFalse(vm.isNoteSaveRunning(request));
		assertTrue("the result is gone before the screen took it",
				vm.isNoteSavePending(request));
		NoteSaveResult result = vm.takeNoteSaveResult(request,
				into(handedBack, arrays));
		assertNotNull(result);
		assertEquals(request, result.request);
		assertEquals(NoteSaveResult.Outcome.FAILED, result.outcome);
		assertEquals(Arrays.asList("Groceries / milk / plain"), handedBack);
		for (char[] kept : arrays) {
			assertArrayEquals("the text was left in memory",
					new char[kept.length], kept);
		}
		assertFalse(vm.isNoteSavePending(request));
		assertNull("the result was handed out twice",
				vm.takeNoteSaveResult(request, into(handedBack, arrays)));
		assertEquals(1, handedBack.size());
	}

	@Test
	public void aStoredSaveHandsNoTextBack() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		List<String> handedBack = new ArrayList<>();
		long request = vm.newNoteSaveRequest();

		vm.saveNote(request, "Groceries", "milk", null);
		idle();

		NoteSaveResult result = vm.takeNoteSaveResult(request,
				into(handedBack, new ArrayList<>()));
		assertNotNull(result);
		assertEquals(NoteSaveResult.Outcome.SAVED, result.outcome);
		assertTrue(handedBack.isEmpty());
	}

	@Test
	public void lockingTheVaultDropsTheTextOfAFailedSave() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addItem(any(), anyString(), any());
		List<String> handedBack = new ArrayList<>();
		long request = vm.newNoteSaveRequest();
		vm.saveNoteWithPassword(request, "Diary", "dear diary",
				"pw".toCharArray(), null);
		idle();

		rig.vault.unlocked = false;
		rig.vault.runLockListeners();

		NoteSaveResult result = vm.takeNoteSaveResult(request,
				into(handedBack, new ArrayList<>()));
		assertNotNull(result);
		assertEquals(NoteSaveResult.Outcome.FAILED, result.outcome);
		assertTrue("the text outlived the lock", handedBack.isEmpty());
	}

	@Test
	public void aResultNobodyTakesIsForgottenTogetherWithItsText()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addItem(any(), anyString(), any());
		List<String> handedBack = new ArrayList<>();
		long first = vm.newNoteSaveRequest();
		vm.saveNote(first, "Groceries", "milk", null);
		long last = first;
		for (int later = 0; later < SaveLedger.KEPT_RESULTS; later++) {
			last = vm.newNoteSaveRequest();
			vm.saveNote(last, "Later", "text", null);
		}
		idle();

		assertFalse(vm.isNoteSavePending(first));
		assertNull(vm.takeNoteSaveResult(first,
				into(handedBack, new ArrayList<>())));
		assertTrue(handedBack.isEmpty());
		assertTrue(vm.isNoteSavePending(last));
		assertNotNull(vm.takeNoteSaveResult(last,
				into(handedBack, new ArrayList<>())));
		assertEquals(Arrays.asList("Later / text / plain"), handedBack);
	}
}
