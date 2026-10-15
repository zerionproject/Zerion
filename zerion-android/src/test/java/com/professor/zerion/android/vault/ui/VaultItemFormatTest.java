package com.professor.zerion.android.vault.ui;

import android.os.Looper;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.model.PasswordEntry;
import com.professor.zerion.android.vault.model.VaultItem;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.lifecycle.LiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.vault.ui.VaultViewModelRig.await;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultItemFormatTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	private static PasswordEntry read(VaultViewModel vm, String id)
			throws Exception {
		AtomicReference<PasswordEntry> got = new AtomicReference<>();
		AtomicReference<String> error = new AtomicReference<>();
		vm.getPassword(id, new VaultViewModel.PasswordCallback() {
			@Override
			public void onPasswordRetrieved(PasswordEntry entry) {
				got.set(entry);
			}

			@Override
			public void onError(String e) {
				error.set(e);
			}
		});
		shadowOf(Looper.getMainLooper()).idle();
		assertNull(error.get());
		assertNotNull(got.get());
		return got.get();
	}

	@Test
	public void passwordEntriesAreStoredAsAFixedJsonShape()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.savePassword("Mail", "me@example.org", "s3cret pw",
				"https://mail.example", "two\nlines\tand \\ slash");
		VaultItem item = rig.vault.named("Mail");
		assertNotNull(item);
		assertEquals(VaultItem.ItemType.PASSWORD, item.type);
		assertEquals("{\"title\":\"Mail\",\"username\":\"me@example.org\","
						+ "\"password\":\"s3cret pw\",\"url\":"
						+ "\"https://mail.example\",\"notes\":"
						+ "\"two\\nlines\\tand \\\\ slash\"}",
				new String(rig.vault.raw(item.id), StandardCharsets.UTF_8));
		PasswordEntry e = read(vm, item.id);
		assertEquals("Mail", e.title);
		assertEquals("me@example.org", e.username);
		assertEquals("s3cret pw", e.password);
		assertEquals("https://mail.example", e.url);
		assertEquals("two\nlines\tand \\ slash", e.notes);
	}

	@Test
	public void aDoubleQuoteInAFieldRoundTrips() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.savePassword("Q", "user", "pa\"ss", "", "");
		VaultItem item = rig.vault.named("Q");
		assertEquals("{\"title\":\"Q\",\"username\":\"user\",\"password\":"
						+ "\"pa\\\"ss\",\"url\":\"\",\"notes\":\"\"}",
				new String(rig.vault.raw(item.id), StandardCharsets.UTF_8));
		assertEquals("pa\"ss", read(vm, item.id).password);
	}

	@Test
	public void aTypedBackslashSequenceRoundTrips() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.savePassword("B", "user", "a\\nb\\", "", "c:\\temp\\\"x\"");
		VaultItem item = rig.vault.named("B");
		PasswordEntry e = read(vm, item.id);
		assertEquals("a\\nb\\", e.password);
		assertEquals("c:\\temp\\\"x\"", e.notes);
	}

	@Test
	public void emptyTitleIsRefused() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.savePassword("  ", "u", "p", "", "");
		assertEquals(0, rig.vault.size());
		assertEquals(rig.app.getString(R.string.vault_error_title_empty),
				vm.getErrorMessage().getValue());
	}

	@Test
	public void plainNotesAreStoredAsUtf8AndReplaced() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.saveNote("Groceries", "milk é", null);
		VaultItem item = rig.vault.named("Groceries");
		assertEquals(VaultItem.ItemType.NOTE, item.type);
		assertArrayEquals("milk é".getBytes(StandardCharsets.UTF_8),
				rig.vault.raw(item.id));
		LiveData<String> loaded = vm.loadNoteContent(item.id);
		await(() -> loaded.getValue() != null);
		assertEquals("milk é", loaded.getValue());

		vm.saveNote("Groceries", "bread", item.id);
		assertEquals(1, rig.vault.size());
		VaultItem replaced = rig.vault.named("Groceries");
		assertTrue(!replaced.id.equals(item.id));
		assertEquals("bread", new String(rig.vault.raw(replaced.id),
				StandardCharsets.UTF_8));
	}

	@Test
	public void protectedNotesUseTheVersionTwoLayout() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.saveNoteWithPassword("Diary", "dear diary", "pw".toCharArray(),
				null);
		VaultItem item = rig.vault.named("🔒 Diary");
		assertNotNull(item);
		assertEquals(VaultItem.ItemType.NOTE, item.type);
		byte[] blob = rig.vault.raw(item.id);
		byte[] plain = "dear diary".getBytes(StandardCharsets.UTF_8);
		assertEquals(0x02, blob[0]);
		assertEquals(1 + 32 + 12 + plain.length + 16, blob.length);
		byte[] salt = Arrays.copyOfRange(blob, 1, 33);
		byte[] iv = Arrays.copyOfRange(blob, 33, 45);
		byte[] key = new Argon2().deriveKey("pw".toCharArray(), salt,
				Argon2.Argon2Params.getDefault());
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
				new GCMParameterSpec(128, iv));
		assertArrayEquals(plain, c.doFinal(blob, 45, blob.length - 45));

		LiveData<String> marker = vm.loadNoteContent(item.id);
		await(() -> marker.getValue() != null);
		assertEquals("__PASSWORD_REQUIRED__", marker.getValue());

		LiveData<String> opened = vm.loadPasswordProtectedNote(item.id,
				"pw".toCharArray());
		await(() -> opened.getValue() != null);
		assertEquals("dear diary", opened.getValue());
	}

	@Test
	public void aWrongPasswordOpensNoNote() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.saveNoteWithPassword("Diary", "dear diary", "pw".toCharArray(),
				null);
		VaultItem item = rig.vault.named("🔒 Diary");
		LiveData<String> opened = vm.loadPasswordProtectedNote(item.id,
				"nope".toCharArray());
		assertNull(opened.getValue());
		assertNull(vm.getErrorMessage().getValue());
	}

	@Test
	public void versionOneNotesStillOpen() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		byte[] salt = new byte[16];
		new SecureRandom().nextBytes(salt);
		salt[0] = 0x11;
		byte[] iv = new byte[12];
		new SecureRandom().nextBytes(iv);
		byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
				.generateSecret(new PBEKeySpec("old".toCharArray(), salt,
						200000, 256)).getEncoded();
		Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
		c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
				new GCMParameterSpec(128, iv));
		byte[] ct = c.doFinal("legacy body".getBytes(StandardCharsets.UTF_8));
		byte[] blob = new byte[16 + 12 + ct.length];
		System.arraycopy(salt, 0, blob, 0, 16);
		System.arraycopy(iv, 0, blob, 16, 12);
		System.arraycopy(ct, 0, blob, 28, ct.length);
		VaultItem item = rig.vault.manager.addItem(VaultItem.ItemType.NOTE,
				"🔒 Old", blob);

		LiveData<String> opened = vm.loadPasswordProtectedNote(item.id,
				"old".toCharArray());
		assertEquals("legacy body", opened.getValue());
		LiveData<String> wrong = vm.loadPasswordProtectedNote(item.id,
				"bad".toCharArray());
		assertNull(wrong.getValue());
	}

	@Test
	public void aLockedVaultAsksTheNoteScreenToRetry() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		vm.saveNote("N", "body", null);
		VaultItem item = rig.vault.named("N");
		rig.vault.unlocked = false;
		LiveData<String> loaded = vm.loadNoteContent(item.id);
		await(() -> loaded.getValue() != null);
		assertEquals("__RETRY__", loaded.getValue());
	}
}
