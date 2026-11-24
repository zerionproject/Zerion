package com.professor.zerion.android.vault.ui;

import android.os.Looper;

import com.professor.zerion.R;
import com.professor.zerion.android.attachment.MediaRefusedException;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.model.VaultItem;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultDocumentUpdateTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static VaultItem existing(VaultViewModelRig rig) throws Exception {
		return rig.vault.manager.addItem(VaultItem.ItemType.DOCUMENT,
				"notes.txt", bytes("first draft"));
	}

	private static VaultItem existingProtected(VaultViewModelRig rig)
			throws Exception {
		return rig.vault.manager.addItemWithPassword(
				VaultItem.ItemType.DOCUMENT, "notes.txt",
				bytes("first draft"), "open sesame".toCharArray());
	}

	private static DocumentSaveResult result(VaultViewModel vm) {
		Event<DocumentSaveResult> event =
				vm.getDocumentSaveResult().getValue();
		assertNotNull("the editor was told nothing", event);
		DocumentSaveResult result = event.getIfNotHandled();
		assertNotNull("the result was already taken", result);
		return result;
	}

	private static List<String> ids(List<VaultItem> items) {
		List<String> ids = new ArrayList<>();
		for (VaultItem item : items) ids.add(item.id);
		return ids;
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	@Test
	public void aRefusedUpdateLeavesTheOldDocumentInPlace() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existing(rig);
		doThrow(MediaRefusedException.cannotClean("refused"))
				.when(rig.vault.manager).addMediaItemWithPassword(any(),
						anyString(), any(), any(), any());
		VaultViewModel vm = rig.newModel();

		vm.updateDocument(vm.newDocumentSaveRequest(),
				old.id, "notes.txt", bytes("second draft"));
		idle();

		assertEquals(1, rig.vault.size());
		VaultItem kept = rig.vault.named("notes.txt");
		assertNotNull(kept);
		assertEquals(old.id, kept.id);
		assertArrayEquals(bytes("first draft"), rig.vault.raw(old.id));
		verify(rig.vault.manager, never()).deleteItem(anyString());
		assertEquals(rig.app.getString(
				R.string.vault_msg_update_document_failed),
				vm.getErrorMessage().getValue());
		assertNull(vm.getSuccessMessage().getValue());
		assertEquals(Boolean.FALSE, vm.getIsLoading().getValue());
	}

	@Test
	public void aFailedUpdateLeavesTheOldDocumentInPlace() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existing(rig);
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addMediaItemWithPassword(any(),
						anyString(), any(), any(), any());
		VaultViewModel vm = rig.newModel();

		vm.updateDocument(vm.newDocumentSaveRequest(),
				old.id, "notes.txt", bytes("second draft"));
		idle();

		assertEquals(1, rig.vault.size());
		assertArrayEquals(bytes("first draft"), rig.vault.raw(old.id));
		verify(rig.vault.manager, never()).deleteItem(anyString());
	}

	@Test
	public void aFailedUpdateTellsTheEditorItFailed() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existing(rig);
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addMediaItemWithPassword(any(),
						anyString(), any(), any(), any());
		VaultViewModel vm = rig.newModel();

		vm.updateDocument(vm.newDocumentSaveRequest(),
				old.id, "notes.txt", bytes("second draft"));
		idle();

		DocumentSaveResult result = result(vm);
		assertEquals(DocumentSaveResult.Outcome.FAILED, result.outcome);
		assertFalse(result.isStored());
		assertNull(result.itemId);
		assertEquals(rig.app.getString(
				R.string.vault_msg_update_document_failed), result.message);
		assertEquals(old.id, vm.latestDocumentId(old.id));
		assertFalse(vm.isEditorSaveRunning());
	}

	@Test
	public void anUpdateOfALockedVaultTellsTheEditorItFailed()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existing(rig);
		VaultViewModel vm = rig.newModel();
		rig.vault.unlocked = false;

		vm.updateDocument(vm.newDocumentSaveRequest(),
				old.id, "notes.txt", bytes("second draft"));
		idle();

		DocumentSaveResult result = result(vm);
		assertEquals(DocumentSaveResult.Outcome.FAILED, result.outcome);
		assertEquals(rig.app.getString(R.string.vault_msg_unlock_first),
				result.message);
		assertArrayEquals(bytes("first draft"), rig.vault.raw(old.id));
		assertFalse(vm.isEditorSaveRunning());
	}

	@Test
	public void anAcceptedUpdateRemovesTheOldDocumentOnlyAfterTheNewOneIsStored()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existing(rig);
		VaultViewModel vm = rig.newModel();

		vm.updateDocument(vm.newDocumentSaveRequest(),
				old.id, "notes.txt", bytes("second draft"));
		idle();

		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named("notes.txt");
		assertNotNull(now);
		assertNotEquals(old.id, now.id);
		assertArrayEquals(bytes("second draft"), rig.vault.raw(now.id));
		InOrder order = inOrder(rig.vault.manager);
		order.verify(rig.vault.manager).addMediaItemWithPassword(
				eq(VaultItem.ItemType.DOCUMENT), eq("notes.txt"), any(),
				eq("text/plain"), isNull());
		order.verify(rig.vault.manager).deleteItem(old.id);
		assertEquals(rig.app.getString(R.string.vault_msg_document_updated),
				vm.getSuccessMessage().getValue());
		assertNull(vm.getErrorMessage().getValue());
	}

	@Test
	public void anAcceptedUpdateTellsTheEditorWhichDocumentHoldsTheText()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existing(rig);
		VaultViewModel vm = rig.newModel();

		vm.updateDocument(vm.newDocumentSaveRequest(),
				old.id, "notes.txt", bytes("second draft"));
		idle();

		VaultItem now = rig.vault.named("notes.txt");
		assertNotNull(now);
		DocumentSaveResult result = result(vm);
		assertEquals(DocumentSaveResult.Outcome.SAVED, result.outcome);
		assertTrue(result.isStored());
		assertEquals(now.id, result.itemId);
		assertEquals(now.id, vm.latestDocumentId(old.id));
		assertEquals(now.id, vm.latestDocumentId(now.id));
		List<VaultItem> listed = vm.getVaultItems().getValue();
		assertNotNull(listed);
		assertEquals(java.util.Collections.singletonList(now.id),
				ids(listed));
		assertFalse(vm.isEditorSaveRunning());
		assertEquals(Boolean.FALSE, vm.getIsLoading().getValue());
	}

	@Test
	public void twoUpdatesInARowLeadToTheLastDocument() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem first = existing(rig);
		VaultViewModel vm = rig.newModel();

		vm.updateDocument(vm.newDocumentSaveRequest(),
				first.id, "notes.txt", bytes("second draft"));
		idle();
		String second = result(vm).itemId;
		assertNotNull(second);
		vm.updateDocument(vm.newDocumentSaveRequest(),
				second, "notes.txt", bytes("third draft"));
		idle();
		String third = result(vm).itemId;

		assertNotNull(third);
		assertEquals(third, vm.latestDocumentId(first.id));
		assertEquals(1, rig.vault.size());
		assertArrayEquals(bytes("third draft"), rig.vault.raw(third));
	}

	@Test
	public void aRemovalThatFailsOnceIsTriedAgain() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existing(rig);
		rig.vault.deleteFailures.add(new java.io.IOException("busy"));
		VaultViewModel vm = rig.newModel();

		vm.updateDocument(vm.newDocumentSaveRequest(),
				old.id, "notes.txt", bytes("second draft"));
		idle();

		verify(rig.vault.manager, times(2)).deleteItem(old.id);
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named("notes.txt");
		assertNotNull(now);
		assertArrayEquals(bytes("second draft"), rig.vault.raw(now.id));
		assertEquals(DocumentSaveResult.Outcome.SAVED, result(vm).outcome);
		assertEquals(rig.app.getString(R.string.vault_msg_document_updated),
				vm.getSuccessMessage().getValue());
		assertNull(vm.getErrorMessage().getValue());
	}

	@Test
	public void aSavedUpdateWhoseEarlierVersionStaysSaysExactlyThat()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existing(rig);
		rig.vault.deleteFailures.add(new java.io.IOException("busy"));
		rig.vault.deleteFailures.add(new SecurityException("locked"));
		VaultViewModel vm = rig.newModel();

		vm.updateDocument(vm.newDocumentSaveRequest(),
				old.id, "notes.txt", bytes("second draft"));
		idle();

		verify(rig.vault.manager, times(2)).deleteItem(old.id);
		assertEquals(2, rig.vault.size());
		assertArrayEquals(bytes("first draft"), rig.vault.raw(old.id));
		DocumentSaveResult result = result(vm);
		assertEquals(DocumentSaveResult.Outcome.SAVED_EARLIER_VERSION_KEPT,
				result.outcome);
		assertTrue(result.isStored());
		assertNotNull(result.itemId);
		assertNotEquals(old.id, result.itemId);
		assertArrayEquals(bytes("second draft"),
				rig.vault.raw(result.itemId));
		String told = rig.app.getString(
				R.string.vault_msg_saved_earlier_version_kept);
		assertEquals(told, result.message);
		assertEquals(told, vm.getErrorMessage().getValue());
		assertNull(vm.getSuccessMessage().getValue());
		assertEquals(result.itemId, vm.latestDocumentId(old.id));
		List<VaultItem> listed = vm.getVaultItems().getValue();
		assertNotNull(listed);
		assertEquals(java.util.Arrays.asList(old.id, result.itemId),
				ids(listed));
		assertEquals(Boolean.FALSE, vm.getIsLoading().getValue());
		assertFalse(vm.isEditorSaveRunning());
	}

	@Test
	public void aPlainSaveNeverStoresAProtectedDocumentWithoutItsPassword()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existingProtected(rig);
		VaultViewModel vm = rig.newModel();

		vm.updateDocument(vm.newDocumentSaveRequest(),
				old.id, "notes.txt", bytes("second draft"));
		idle();

		DocumentSaveResult result = result(vm);
		assertEquals(DocumentSaveResult.Outcome.PASSWORD_REQUIRED,
				result.outcome);
		assertFalse(result.isStored());
		assertEquals(rig.app.getString(
				R.string.vault_doc_password_needed_to_save), result.message);
		verify(rig.vault.manager, never()).addMediaItemWithPassword(any(),
				anyString(), any(), any(), any());
		verify(rig.vault.manager, never()).deleteItem(anyString());
		assertEquals(1, rig.vault.size());
		assertTrue(rig.vault.manager.itemHasExtraPassword(old.id));
		assertArrayEquals(bytes("first draft"), rig.vault.raw(old.id));
	}

	@Test
	public void aProtectedDocumentSavedWithItsPasswordStaysProtected()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existingProtected(rig);
		VaultViewModel vm = rig.newModel();
		char[] typed = "open sesame".toCharArray();
		byte[] text = bytes("second draft");

		vm.updateProtectedDocument(vm.newDocumentSaveRequest(),
				old.id, "notes.txt", text, typed);
		idle();

		DocumentSaveResult result = result(vm);
		assertEquals(DocumentSaveResult.Outcome.SAVED, result.outcome);
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named("notes.txt");
		assertNotNull(now);
		assertNotEquals(old.id, now.id);
		assertTrue(now.hasExtraPassword);
		assertTrue(rig.vault.manager.itemHasExtraPassword(now.id));
		assertArrayEquals(bytes("second draft"),
				rig.vault.manager.getItemContentWithPassword(now.id,
						"open sesame".toCharArray()));
		assertArrayEquals("the typed password stays in memory",
				new char[typed.length], typed);
		assertArrayEquals("the text stays in memory", new byte[text.length],
				text);
	}

	@Test
	public void aWrongPasswordStoresNothingAndKeepsTheProtectedDocument()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existingProtected(rig);
		VaultViewModel vm = rig.newModel();
		char[] typed = "open says me".toCharArray();

		vm.updateProtectedDocument(vm.newDocumentSaveRequest(),
				old.id, "notes.txt",
				bytes("second draft"), typed);
		idle();

		DocumentSaveResult result = result(vm);
		assertEquals(DocumentSaveResult.Outcome.WRONG_PASSWORD,
				result.outcome);
		assertEquals(rig.app.getString(R.string.vault_incorrect_password),
				result.message);
		verify(rig.vault.manager, never()).addMediaItemWithPassword(any(),
				anyString(), any(), any(), any());
		verify(rig.vault.manager, never()).deleteItem(anyString());
		assertEquals(1, rig.vault.size());
		assertArrayEquals(bytes("first draft"),
				rig.vault.manager.getItemContentWithPassword(old.id,
						"open sesame".toCharArray()));
		assertArrayEquals(new char[typed.length], typed);
	}

	@Test
	public void aSaveWithAPasswordReplacesTheUnprotectedOriginal()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existing(rig);
		VaultViewModel vm = rig.newModel();
		char[] chosen = "new secret".toCharArray();

		vm.replaceDocumentWithPassword(vm.newDocumentSaveRequest(),
				old.id, "notes.txt",
				bytes("second draft"), chosen);
		idle();

		assertEquals(DocumentSaveResult.Outcome.SAVED, result(vm).outcome);
		assertEquals("the unprotected original stays beside the copy", 1,
				rig.vault.size());
		VaultItem now = rig.vault.named("notes.txt");
		assertNotNull(now);
		assertNotEquals(old.id, now.id);
		assertTrue(now.hasExtraPassword);
		assertArrayEquals(bytes("second draft"),
				rig.vault.manager.getItemContentWithPassword(now.id,
						"new secret".toCharArray()));
		InOrder order = inOrder(rig.vault.manager);
		order.verify(rig.vault.manager).addMediaItemWithPassword(
				eq(VaultItem.ItemType.DOCUMENT), eq("notes.txt"), any(),
				eq("text/plain"), any(char[].class));
		order.verify(rig.vault.manager).deleteItem(old.id);
		assertArrayEquals(new char[chosen.length], chosen);
	}

	@Test
	public void aSaveWithANewPasswordReplacesAProtectedDocument()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existingProtected(rig);
		VaultViewModel vm = rig.newModel();

		vm.replaceDocumentWithPassword(vm.newDocumentSaveRequest(),
				old.id, "notes.txt",
				bytes("second draft"), "new secret".toCharArray());
		idle();

		assertEquals(DocumentSaveResult.Outcome.SAVED, result(vm).outcome);
		assertEquals(1, rig.vault.size());
		VaultItem now = rig.vault.named("notes.txt");
		assertNotNull(now);
		assertArrayEquals(bytes("second draft"),
				rig.vault.manager.getItemContentWithPassword(now.id,
						"new secret".toCharArray()));
	}

	@Test
	public void aNewDocumentFromTheEditorReportsItsResult() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();

		vm.addDocumentWithPassword(vm.newDocumentSaveRequest(),
				"fresh.txt", bytes("hello"), null);
		idle();

		VaultItem stored = rig.vault.named("fresh.txt");
		assertNotNull(stored);
		DocumentSaveResult result = result(vm);
		assertEquals(DocumentSaveResult.Outcome.SAVED, result.outcome);
		assertEquals(stored.id, result.itemId);
		assertEquals(rig.app.getString(R.string.vault_msg_document_saved),
				vm.getSuccessMessage().getValue());
		assertFalse(vm.isEditorSaveRunning());
	}

	@Test
	public void aNewDocumentThatCannotBeStoredReportsTheFailure()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		doThrow(new java.io.IOException("disk full"))
				.when(rig.vault.manager).addMediaItemWithPassword(any(),
						anyString(), any(), any(), any());
		VaultViewModel vm = rig.newModel();
		char[] chosen = "new secret".toCharArray();

		vm.addDocumentWithPassword(vm.newDocumentSaveRequest(),
				"fresh.txt", bytes("hello"), chosen);
		idle();

		DocumentSaveResult result = result(vm);
		assertEquals(DocumentSaveResult.Outcome.FAILED, result.outcome);
		assertEquals(rig.app.getString(
				R.string.vault_msg_add_document_failed), result.message);
		assertEquals(0, rig.vault.size());
		assertArrayEquals(new char[chosen.length], chosen);
		assertFalse(vm.isEditorSaveRunning());
	}

	@Test
	public void anImportedDocumentSendsNothingToTheEditor() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();

		vm.addDocumentWithPassword("scan.pdf", bytes("%PDF-1.4"),
				"application/pdf", null);
		idle();

		assertNotNull(rig.vault.named("scan.pdf"));
		assertNull(vm.getDocumentSaveResult().getValue());
		assertFalse(vm.isEditorSaveRunning());
		assertEquals(rig.app.getString(R.string.vault_msg_document_saved),
				vm.getSuccessMessage().getValue());
	}
}
