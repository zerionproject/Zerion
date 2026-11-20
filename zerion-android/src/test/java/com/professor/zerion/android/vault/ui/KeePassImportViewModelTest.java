package com.professor.zerion.android.vault.ui;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.model.PasswordEntry;
import com.professor.zerion.android.vault.model.PasswordEntryCodec;
import com.professor.zerion.android.vault.model.VaultItem;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class KeePassImportViewModelTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	private static byte[] fixture(String name) throws Exception {
		try (InputStream in = KeePassImportViewModelTest.class
				.getResourceAsStream("/keepass/" + name)) {
			if (in == null) throw new IllegalStateException("missing " + name);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[8192];
			int n;
			while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
			return out.toByteArray();
		}
	}

	@Test
	public void entriesAreStoredAsPasswordItemsThroughTheCodec()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		byte[] file = fixture("kdbx4_argon2d_aes_chacha20.kdbx");
		char[] password = "correct horse".toCharArray();
		vm.importKeePass(file, password, null);
		VaultViewModelRig.settle();
		assertEquals(9, rig.vault.size());
		for (byte b : file) assertEquals(0, b);
		for (char c : password) assertEquals(0, c);
		List<String> names = new ArrayList<>();
		for (VaultItem item : rig.vault.list()) {
			assertEquals(VaultItem.ItemType.PASSWORD, item.type);
			names.add(item.name);
		}
		assertTrue(names.contains("db01"));
		assertTrue(names.contains("login.example.net"));
		assertTrue(names.contains(rig.app.getString(
				R.string.vault_keepass_untitled)));
		VaultItem db01 = rig.vault.named("db01");
		String json = new String(rig.vault.raw(db01.id), StandardCharsets.UTF_8);
		assertFalse(PasswordEntryCodec.isBookmark(json));
		PasswordEntry entry = PasswordEntryCodec.decode(json);
		assertEquals("root", entry.username);
		assertEquals("pw-db01", entry.password);
		assertEquals("", entry.url);
		assertTrue(entry.notes.startsWith("Folder: Work / Servers\nPostgres\n"));
		assertTrue(entry.notes.contains("API Key: secret-api-key"));
		assertEquals(rig.app.getString(R.string.vault_keepass_imported_skipped,
				9, 6), vm.getSuccessMessage().getValue());
		assertNull(vm.getErrorMessage().getValue());
		assertEquals(Boolean.FALSE, vm.getIsLoading().getValue());
		assertEquals(9, vm.getVaultItems().getValue().size());
	}

	@Test
	public void wrongPasswordLeavesTheVaultUntouchedAndSaysOneThing()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.importKeePass(fixture("kdbx4_aeskdf.kdbx"),
				"wrong horse".toCharArray(), null);
		VaultViewModelRig.settle();
		assertEquals(0, rig.vault.size());
		assertEquals(rig.app.getString(R.string.vault_keepass_open_failed),
				vm.getErrorMessage().getValue());
		assertNull(vm.getSuccessMessage().getValue());
		assertEquals(Boolean.FALSE, vm.getIsLoading().getValue());
	}

	@Test
	public void unsupportedFileGetsItsOwnMessage() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.importKeePass("not a database".getBytes(StandardCharsets.UTF_8),
				"correct horse".toCharArray(), null);
		VaultViewModelRig.settle();
		assertEquals(0, rig.vault.size());
		assertEquals(rig.app.getString(R.string.vault_keepass_unsupported),
				vm.getErrorMessage().getValue());
	}

	@Test
	public void lockedVaultRefusesTheImport() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		rig.vault.unlocked = false;
		VaultViewModel vm = rig.newModel();
		byte[] file = fixture("kdbx4_aeskdf.kdbx");
		vm.importKeePass(file, "correct horse".toCharArray(), null);
		VaultViewModelRig.settle();
		assertEquals(0, rig.vault.size());
		for (byte b : file) assertEquals(0, b);
		assertEquals(rig.app.getString(R.string.vault_msg_unlock_first),
				vm.getErrorMessage().getValue());
	}
}
