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

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.vault.ui.VaultViewModelRig.await;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
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
		vm.saveNote("Groceries", "milk", null);
		idle();
		VaultItem note = rig.vault.named("Groceries");
		assertNotNull(note);
		vm.clearMessages();
		clearInvocations(rig.vault.manager);
		return note;
	}

	private static VaultItem protectedNote(VaultViewModelRig rig,
			VaultViewModel vm) throws Exception {
		vm.saveNoteWithPassword("Diary", "dear diary", "pw".toCharArray(),
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

		vm.saveNote("Groceries", "bread", old.id);
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

		vm.saveNote("Groceries", "bread", old.id);
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

		vm.saveNote("Groceries", "bread", old.id);
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

		vm.saveNote("Groceries", "bread", old.id);
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

		vm.saveNoteWithPassword("Diary", "second entry", typed, old.id);
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

		vm.saveNoteWithPassword("Diary", "second entry", "pw".toCharArray(),
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

		vm.saveNoteWithPassword("Diary", "second entry", "pw".toCharArray(),
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
}
