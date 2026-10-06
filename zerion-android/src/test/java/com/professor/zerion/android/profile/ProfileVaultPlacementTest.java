package com.professor.zerion.android.profile;

import android.content.Context;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.crypto.VaultKeystore;
import com.professor.zerion.android.vault.storage.VaultLocation;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.util.IoUtils;

import java.io.File;
import java.nio.file.Files;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.profile.ProfileStorageTestSupport.addProfile;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.notSignedIn;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.profileDir;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.signedIn;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ProfileVaultPlacementTest {

	static {
		TestAndroidKeyStore.register();
	}

	private Context app;
	private File deviceVault;
	private File deviceXmr;

	@Before
	public void setUp() {
		app = RuntimeEnvironment.getApplication();
		com.professor.zerion.android.AppModule.getAndroidComponent(app)
				.accountManager();
		deviceVault = new File(app.getNoBackupFilesDir(), "vault");
		deviceXmr = new File(app.getNoBackupFilesDir(), "xmr");
	}

	@After
	public void tearDown() {
		IoUtils.deleteFileOrDir(new File(app.getFilesDir(), "profiles"));
		IoUtils.deleteFileOrDir(deviceVault);
		IoUtils.deleteFileOrDir(deviceXmr);
		new File(app.getNoBackupFilesDir(), ProfileStorage.DEVICE_VAULT_CLAIM)
				.delete();
	}

	private void deviceVaultWithWallet() throws Exception {
		assertTrue(deviceVault.mkdirs());
		Files.write(new File(deviceVault, "vault.header").toPath(),
				new byte[] {1});
		assertTrue(deviceXmr.mkdirs());
		Files.write(new File(deviceXmr, "w.keys").toPath(), new byte[] {2});
	}

	private static File walletXmrDir(ProfileStorage storage) {
		Context c = storage.walletContext();
		return new File(c.getApplicationContext().getNoBackupFilesDir(), "xmr");
	}

	@Test
	public void eachProfileHasItsOwnVaultKeyAndWalletFiles() throws Exception {
		addProfile(app, "default");
		addProfile(app, "b7c1");

		ProfileStorage decoy = signedIn(app, "default");
		VaultLocation decoyVault = decoy.vaultLocation();
		ProfileStorage hidden = signedIn(app, "b7c1");
		VaultLocation hiddenVault = hidden.vaultLocation();

		assertEquals(new File(profileDir(app, "default"), "vault"),
				decoyVault.directory());
		assertEquals(new File(profileDir(app, "b7c1"), "vault"),
				hiddenVault.directory());
		assertNotEquals(decoyVault.keyAlias(), hiddenVault.keyAlias());
		assertNotEquals(VaultKeystore.LEGACY_KEY_ALIAS, decoyVault.keyAlias());
		assertFalse(decoyVault.keyAlias().contains("b7c1"));
		assertEquals(new File(profileDir(app, "b7c1"), "wallet/xmr"),
				walletXmrDir(hidden));
		assertFalse(decoy.vaultIsShared());
	}

	@Test
	public void theDeviceVaultMovesIntoTheOnlyProfile() throws Exception {
		addProfile(app, "default");
		deviceVaultWithWallet();

		ProfileStorage storage = signedIn(app, "default");
		VaultLocation vault = storage.vaultLocation();

		File own = new File(profileDir(app, "default"), "vault");
		assertEquals(own, vault.directory());
		assertTrue(new File(own, "vault.header").exists());
		assertFalse(deviceVault.exists());
		assertEquals("the moved vault keeps its key",
				VaultKeystore.LEGACY_KEY_ALIAS, vault.keyAlias());
		assertTrue(new File(walletXmrDir(storage), "w.keys").exists());
		assertFalse(deviceXmr.exists());
	}

	@Test
	public void withSeveralProfilesTheDeviceVaultStaysSharedWhenOnlyUnlocked()
			throws Exception {
		addProfile(app, "default");
		addProfile(app, "b7c1");
		deviceVaultWithWallet();

		ProfileStorage decoy = signedIn(app, "default");
		assertTrue(decoy.vaultIsShared());
		assertEquals(deviceVault, decoy.vaultLocation().directory());
		ProfileStorage hidden = signedIn(app, "b7c1");
		assertTrue(hidden.vaultIsShared());
		assertEquals(deviceXmr, walletXmrDir(hidden));

		hidden.vaultLocation().onUnlocked();

		ProfileStorage hiddenAgain = signedIn(app, "b7c1");
		assertTrue("unlocking alone claims nothing",
				hiddenAgain.vaultIsShared());
		assertEquals(deviceVault, hiddenAgain.vaultLocation().directory());
		assertTrue(new File(deviceVault, "vault.header").exists());
		assertEquals(deviceXmr, walletXmrDir(hiddenAgain));
		ProfileStorage decoyAgain = signedIn(app, "default");
		assertTrue(decoyAgain.vaultIsShared());
		assertEquals(deviceVault, decoyAgain.vaultLocation().directory());
	}

	@Test
	public void anEraseBeforeSignInErasesEveryVault() throws Exception {
		addProfile(app, "default");
		addProfile(app, "b7c1");
		deviceVaultWithWallet();
		File a = new File(profileDir(app, "default"), "vault");
		File b = new File(profileDir(app, "b7c1"), "vault");
		assertTrue(a.mkdirs());
		assertTrue(b.mkdirs());
		Files.write(new File(a, "vault.header").toPath(), new byte[] {3});
		Files.write(new File(b, "vault.header").toPath(), new byte[] {4});

		new VaultManager(app, notSignedIn(app).vaultLocation()).wipeVault();

		assertFalse(deviceVault.exists());
		assertFalse(a.exists());
		assertFalse(b.exists());
	}

	@Test
	public void aProfileErasesOnlyItsOwnVault() throws Exception {
		addProfile(app, "default");
		addProfile(app, "b7c1");
		File a = new File(profileDir(app, "default"), "vault");
		File b = new File(profileDir(app, "b7c1"), "vault");
		assertTrue(a.mkdirs());
		assertTrue(b.mkdirs());
		Files.write(new File(a, "vault.header").toPath(), new byte[] {3});
		Files.write(new File(b, "vault.header").toPath(), new byte[] {4});

		VaultManager decoy = new VaultManager(app,
				signedIn(app, "default").vaultLocation());
		assertTrue(decoy.vaultExists());
		decoy.wipeVault();

		assertFalse(new File(a, "vault.header").exists());
		assertTrue(new File(b, "vault.header").exists());
		assertFalse("nothing before sign-in", new VaultManager(app,
				notSignedIn(app).vaultLocation()).vaultExists());
	}
}
