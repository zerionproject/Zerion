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

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

/**
 * Editing a vault document stores the new text first and removes the old
 * item only once the new one is in the vault, so a refused or failed save
 * never loses the document that was there.
 */
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

	@Test
	public void aRefusedUpdateLeavesTheOldDocumentInPlace() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existing(rig);
		when(rig.vault.manager.addMediaItemWithPassword(any(), anyString(),
				any(), any(), any()))
				.thenThrow(MediaRefusedException.cannotClean("refused"));
		VaultViewModel vm = rig.newModel();

		vm.updateDocument(old.id, "notes.txt", bytes("second draft"));
		shadowOf(Looper.getMainLooper()).idle();

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
		when(rig.vault.manager.addMediaItemWithPassword(any(), anyString(),
				any(), any(), any()))
				.thenThrow(new java.io.IOException("disk full"));
		VaultViewModel vm = rig.newModel();

		vm.updateDocument(old.id, "notes.txt", bytes("second draft"));
		shadowOf(Looper.getMainLooper()).idle();

		assertEquals(1, rig.vault.size());
		assertArrayEquals(bytes("first draft"), rig.vault.raw(old.id));
		verify(rig.vault.manager, never()).deleteItem(anyString());
	}

	@Test
	public void anAcceptedUpdateRemovesTheOldDocumentOnlyAfterTheNewOneIsStored()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultItem old = existing(rig);
		when(rig.vault.manager.addMediaItemWithPassword(any(), anyString(),
				any(), any(), any())).thenAnswer(a ->
				rig.vault.manager.addItem(a.getArgument(0), a.getArgument(1),
						a.getArgument(2)));
		VaultViewModel vm = rig.newModel();

		vm.updateDocument(old.id, "notes.txt", bytes("second draft"));
		shadowOf(Looper.getMainLooper()).idle();

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
}
