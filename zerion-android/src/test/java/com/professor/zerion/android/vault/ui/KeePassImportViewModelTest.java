package com.professor.zerion.android.vault.ui;

import android.net.Uri;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.model.PasswordEntry;
import com.professor.zerion.android.vault.model.PasswordEntryCodec;
import com.professor.zerion.android.vault.model.VaultItem;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class KeePassImportViewModelTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	private static final String FULL = "kdbx4_argon2d_aes_chacha20.kdbx";
	private static final char[] PASSWORD = "correct horse".toCharArray();

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

	private static int headerLength(byte[] file) {
		int pos = 12;
		while (true) {
			int id = file[pos] & 0xFF;
			int size = (file[pos + 1] & 0xFF) | ((file[pos + 2] & 0xFF) << 8)
					| ((file[pos + 3] & 0xFF) << 16)
					| ((file[pos + 4] & 0xFF) << 24);
			pos += 5 + size;
			if (id == 0) return pos;
		}
	}

	private static String text(VaultViewModelRig rig, int id, Object... args) {
		return rig.app.getString(id, args);
	}

	@Test
	public void entriesAreStoredAsPasswordItemsThroughTheCodec()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		byte[] file = fixture(FULL);
		char[] password = PASSWORD.clone();
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
		assertTrue(names.contains(text(rig, R.string.vault_keepass_untitled)));
		VaultItem db01 = rig.vault.named("db01");
		String json = new String(rig.vault.raw(db01.id), StandardCharsets.UTF_8);
		assertFalse(PasswordEntryCodec.isBookmark(json));
		PasswordEntry entry = PasswordEntryCodec.decode(json);
		assertEquals("root", entry.username);
		assertEquals("pw-db01", entry.password);
		assertEquals("", entry.url);
		assertTrue(entry.notes.startsWith(
				text(rig, R.string.vault_keepass_folder)
						+ ": Work / Servers\nPostgres\n"));
		assertTrue(entry.notes.contains("API Key: secret-api-key"));
		assertEquals(text(rig, R.string.vault_keepass_imported_skipped, 9, 4),
				vm.getSuccessMessage().getValue());
		assertNull(vm.getErrorMessage().getValue());
		assertEquals(Boolean.FALSE, vm.getIsLoading().getValue());
		assertEquals(Integer.valueOf(0), vm.getProgressPercent().getValue());
		assertEquals(9, vm.getVaultItems().getValue().size());
	}

	@Test
	public void nothingSkippedGivesTheShortSuccessText() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.importKeePass(fixture("kdbx4_simple.kdbx"), PASSWORD.clone(), null);
		VaultViewModelRig.settle();
		assertEquals(2, rig.vault.size());
		assertEquals(text(rig, R.string.vault_keepass_imported, 2),
				vm.getSuccessMessage().getValue());
	}

	@Test
	public void wrongPasswordAndDamagedFileSayTheSameThing() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.importKeePass(fixture("kdbx4_aeskdf.kdbx"),
				"wrong horse".toCharArray(), null);
		VaultViewModelRig.settle();
		assertEquals(0, rig.vault.size());
		String oneMessage = text(rig, R.string.vault_keepass_open_failed);
		assertEquals(oneMessage, vm.getErrorMessage().getValue());
		assertNull(vm.getSuccessMessage().getValue());
		assertEquals(Boolean.FALSE, vm.getIsLoading().getValue());
		vm.clearMessages();
		byte[] damaged = fixture(FULL);
		damaged[headerLength(damaged) + 64 + 36 + 2] ^= 0x01;
		vm.importKeePass(damaged, PASSWORD.clone(), null);
		VaultViewModelRig.settle();
		assertEquals(0, rig.vault.size());
		assertEquals(oneMessage, vm.getErrorMessage().getValue());
	}

	@Test
	public void unsupportedAndOversizedFilesGetTheirOwnMessages()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.importKeePass("not a database".getBytes(StandardCharsets.UTF_8),
				PASSWORD.clone(), null);
		VaultViewModelRig.settle();
		assertEquals(text(rig, R.string.vault_keepass_unsupported),
				vm.getErrorMessage().getValue());
		vm.clearMessages();
		byte[] file = fixture(FULL);
		int header = headerLength(file);
		byte[] item = {0x05, 0x01, 0x00, 0x00, 0x00, 'M', 0x08, 0x00, 0x00, 0x00};
		int at = -1;
		outer:
		for (int i = 0; i + item.length <= header; i++) {
			for (int j = 0; j < item.length; j++) {
				if (file[i + j] != item[j]) continue outer;
			}
			at = i + item.length;
			break;
		}
		assertTrue(at > 0);
		long huge = 1L << 40;
		for (int i = 0; i < 8; i++) file[at + i] = (byte) (huge >>> (8 * i));
		byte[] hash = MessageDigest.getInstance("SHA-256")
				.digest(Arrays.copyOf(file, header));
		System.arraycopy(hash, 0, file, header, 32);
		vm.importKeePass(file, PASSWORD.clone(), null);
		VaultViewModelRig.settle();
		assertEquals(text(rig, R.string.vault_keepass_too_large),
				vm.getErrorMessage().getValue());
		assertEquals(0, rig.vault.size());
	}

	@Test
	public void failureHalfWayReportsHowManyWereSaved() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		AtomicInteger calls = new AtomicInteger();
		doAnswer(a -> {
			if (calls.incrementAndGet() == 3) throw new IOException("disk");
			return rig.vault.manager.addMediaItem(a.getArgument(0),
					a.getArgument(1), a.getArgument(2), null);
		}).when(rig.vault.manager).addItem(any(), anyString(), any());
		VaultViewModel vm = rig.newModel();
		vm.importKeePass(fixture(FULL), PASSWORD.clone(), null);
		VaultViewModelRig.settle();
		assertEquals(text(rig, R.string.vault_keepass_imported_partial, 2, 9),
				vm.getErrorMessage().getValue());
		assertNull(vm.getSuccessMessage().getValue());
		assertEquals(Boolean.FALSE, vm.getIsLoading().getValue());
	}

	@Test
	public void lockedVaultRefusesTheImport() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		rig.vault.unlocked = false;
		VaultViewModel vm = rig.newModel();
		byte[] file = fixture("kdbx4_aeskdf.kdbx");
		vm.importKeePass(file, PASSWORD.clone(), null);
		VaultViewModelRig.settle();
		assertEquals(0, rig.vault.size());
		for (byte b : file) assertEquals(0, b);
		assertEquals(text(rig, R.string.vault_msg_unlock_first),
				vm.getErrorMessage().getValue());
	}

	@Test
	public void pickedFilesAreHeldByTheModelUntilImportOrCancel()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		Uri key = Uri.parse("content://test/keyfile.key");
		Uri db = Uri.parse("content://test/db.kdbx");
		shadowOf(rig.app.getContentResolver()).registerInputStream(key,
				new ByteArrayInputStream(fixture("keyfile_raw.key")));
		vm.readKeePassFile(key, true);
		VaultViewModelRig.settle();
		assertFalse("a key file without a database is dropped",
				vm.hasKeePassKeyFile());
		assertNull(vm.getKeePassDatabaseName().getValue());
		shadowOf(rig.app.getContentResolver()).registerInputStream(db,
				new ByteArrayInputStream(fixture("kdbx4_keyfile_raw.kdbx")));
		vm.readKeePassFile(db, false);
		VaultViewModelRig.settle();
		assertEquals("db.kdbx", vm.getKeePassDatabaseName().getValue());
		shadowOf(rig.app.getContentResolver()).registerInputStream(key,
				new ByteArrayInputStream(fixture("keyfile_raw.key")));
		vm.readKeePassFile(key, true);
		VaultViewModelRig.settle();
		assertTrue(vm.hasKeePassKeyFile());
		assertEquals("keyfile.key", vm.getKeePassKeyFileName().getValue());
		vm.cancelKeePassImport();
		VaultViewModelRig.settle();
		assertFalse(vm.hasKeePassKeyFile());
		assertNull(vm.getKeePassDatabaseName().getValue());
		assertNull(vm.getKeePassKeyFileName().getValue());
		char[] password = PASSWORD.clone();
		vm.importKeePass(password);
		VaultViewModelRig.settle();
		for (char c : password) assertEquals(0, c);
		assertEquals(0, rig.vault.size());
		shadowOf(rig.app.getContentResolver()).registerInputStream(db,
				new ByteArrayInputStream(fixture("kdbx4_keyfile_raw.kdbx")));
		vm.readKeePassFile(db, false);
		VaultViewModelRig.settle();
		shadowOf(rig.app.getContentResolver()).registerInputStream(key,
				new ByteArrayInputStream(fixture("keyfile_raw.key")));
		vm.readKeePassFile(key, true);
		VaultViewModelRig.settle();
		vm.importKeePass(PASSWORD.clone());
		VaultViewModelRig.settle();
		assertEquals(9, rig.vault.size());
		assertFalse(vm.hasKeePassKeyFile());
		assertNull(vm.getKeePassDatabaseName().getValue());
	}

	@Test
	public void aFileReadWhileTheVaultIsLockedIsDropped() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		Uri db = Uri.parse("content://test/db.kdbx");
		shadowOf(rig.app.getContentResolver()).registerInputStream(db,
				new ByteArrayInputStream(fixture("kdbx4_simple.kdbx")));
		rig.vault.unlocked = false;
		vm.readKeePassFile(db, false);
		VaultViewModelRig.settle();
		assertNull(vm.getKeePassDatabaseName().getValue());
		assertEquals(text(rig, R.string.vault_msg_unlock_first),
				vm.getErrorMessage().getValue());
	}

	@Test
	public void lockMidStoreReportsThePartialCount() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		AtomicInteger calls = new AtomicInteger();
		doAnswer(a -> {
			if (calls.incrementAndGet() == 2) throw new SecurityException("locked");
			return rig.vault.manager.addMediaItem(a.getArgument(0),
					a.getArgument(1), a.getArgument(2), null);
		}).when(rig.vault.manager).addItem(any(), anyString(), any());
		VaultViewModel vm = rig.newModel();
		vm.importKeePass(fixture("kdbx4_simple.kdbx"), PASSWORD.clone(), null);
		VaultViewModelRig.settle();
		assertEquals(text(rig, R.string.vault_keepass_imported_partial, 1, 2),
				vm.getErrorMessage().getValue());
	}

	@Test
	public void lockingTheVaultDropsThePickedFiles() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		Uri db = Uri.parse("content://test/db.kdbx");
		shadowOf(rig.app.getContentResolver()).registerInputStream(db,
				new ByteArrayInputStream(fixture("kdbx4_simple.kdbx")));
		vm.readKeePassFile(db, false);
		VaultViewModelRig.settle();
		assertEquals("db.kdbx", vm.getKeePassDatabaseName().getValue());
		vm.clearSensitiveMemory();
		VaultViewModelRig.settle();
		assertNull(vm.getKeePassDatabaseName().getValue());
		vm.importKeePass(PASSWORD.clone());
		VaultViewModelRig.settle();
		assertEquals(0, rig.vault.size());
	}
}
