package com.professor.zerion.android.vault;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.storage.SecureFileIO;
import com.professor.zerion.android.vault.storage.VaultLocation;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class VaultRekeyRecoveryDeviceTest {

	private static final char[] PW1 = "master-one-123".toCharArray();
	private static final char[] PW2 = "master-two-456".toCharArray();
	private static final byte[] SEED =
			"seed-material-do-not-lose".getBytes(StandardCharsets.UTF_8);

	private static final String TEST_ALIAS = "zerion_test_vault_rekey";

	private Context ctx;
	private File vaultDir;
	private VaultLocation location;
	private SecureFileIO fileIO;

	@Before
	public void setUp() throws Exception {
		ctx = ApplicationProvider.getApplicationContext();
		vaultDir = new File(ctx.getCacheDir(), "test-vault-rekey-recovery");
		deleteTree(vaultDir);
		deleteTestKeys();
		location = new VaultLocation() {
			@Override
			public boolean isAvailable() {
				return true;
			}

			@Override
			public File directory() {
				return vaultDir;
			}

			@Override
			public String keyAlias() {
				return TEST_ALIAS;
			}

			@Override
			public List<File> allVaultDirectories() {
				return Collections.singletonList(vaultDir);
			}

			@Override
			public void onUnlocked() {
			}
		};
		fileIO = new SecureFileIO(ctx, location);
	}

	@After
	public void tearDown() throws Exception {
		deleteTree(vaultDir);
		deleteTestKeys();
	}

	private static void deleteTestKeys() throws Exception {
		KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
		ks.load(null);
		for (String alias : Collections.list(ks.aliases())) {
			if (alias.startsWith(TEST_ALIAS)) ks.deleteEntry(alias);
		}
	}

	private static void deleteTree(File f) {
		if (f == null || !f.exists()) return;
		File[] kids = f.listFiles();
		if (kids != null) for (File k : kids) deleteTree(k);
		f.delete();
	}

	private VaultManager fresh() {
		return new VaultManager(ctx, location);
	}

	private static void awaitUnlockThrottle() throws InterruptedException {
		Thread.sleep(2500);
	}

	private String addSeed(VaultManager v) throws Exception {
		VaultItem item = v.addItem(VaultItem.ItemType.WALLET, "XMR\nAcc", SEED);
		return item.id;
	}

	private void assertNoLeftovers() {
		File dir = fileIO.getVaultDir();
		assertFalse("no header marker left", new File(dir, "vault.header.new").exists());
		assertFalse("no backup dir left", new File(dir, "items_rekey_backup").exists());
		assertFalse("no temp dir left", new File(dir, "items_rekey_temp").exists());
	}

	@Test
	public void changePasswordPreservesItemsAndSwitchesPassword()
			throws Exception {
		VaultManager v = fresh();
		v.createVault(PW1.clone());
		String id = addSeed(v);
		v.changePassword(PW1.clone(), PW2.clone());

		assertFalse("old password no longer unlocks",
				fresh().unlockVault(PW1.clone()));
		awaitUnlockThrottle();
		VaultManager reopened = fresh();
		assertTrue("new password unlocks", reopened.unlockVault(PW2.clone()));
		assertArrayEquals("item content survives the password change", SEED,
				reopened.getItemContent(id));
		assertNoLeftovers();
	}

	@Test
	public void strayHeaderMarkerAndTempAreReconciledOnUnlock()
			throws Exception {
		VaultManager v = fresh();
		v.createVault(PW1.clone());
		String id = addSeed(v);
		v.lockVault();

		File dir = fileIO.getVaultDir();
		write(new File(dir, "vault.header.new"), new byte[]{1, 2, 3});
		assertTrue(new File(dir, "items_rekey_temp").mkdirs());

		VaultManager reopened = fresh();
		assertTrue("an interrupted, uncommitted change rolls back so the "
				+ "original password still unlocks",
				reopened.unlockVault(PW1.clone()));
		assertArrayEquals("the item is intact after rollback", SEED,
				reopened.getItemContent(id));
		assertNoLeftovers();
	}

	@Test
	public void getItemContentWithPasswordDoesNotMutateTheInputBuffer()
			throws Exception {
		VaultManager v = fresh();
		v.createVault(PW1.clone());
		char[] itemPw = "wallet-secret-pw".toCharArray();
		VaultItem item = v.addItemWithPassword(VaultItem.ItemType.WALLET,
				"XMR\nAcc", SEED, itemPw.clone());
		char[] input = "wallet-secret-pw".toCharArray();
		byte[] content = v.getItemContentWithPassword(item.id, input);
		assertArrayEquals("the decrypt must not mutate the caller's password",
				"wallet-secret-pw".toCharArray(), input);
		assertArrayEquals("and it still decrypts", SEED, content);
	}

	@Test
	public void strayBackupIsCleanedOnUnlock() throws Exception {
		VaultManager v = fresh();
		v.createVault(PW1.clone());
		String id = addSeed(v);
		v.lockVault();

		assertTrue(new File(fileIO.getVaultDir(), "items_rekey_backup").mkdirs());

		VaultManager reopened = fresh();
		assertTrue("a committed change with a leftover backup still unlocks",
				reopened.unlockVault(PW1.clone()));
		assertArrayEquals(SEED, reopened.getItemContent(id));
		assertNoLeftovers();
	}

	private static void write(File f, byte[] bytes) throws Exception {
		try (FileOutputStream out = new FileOutputStream(f)) {
			out.write(bytes);
		}
	}
}
