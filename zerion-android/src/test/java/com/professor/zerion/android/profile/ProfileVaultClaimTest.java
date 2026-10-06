package com.professor.zerion.android.profile;

import android.content.Context;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.crypto.VaultKeystore;

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
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.profileDir;
import static com.professor.zerion.android.profile.ProfileStorageTestSupport.signedIn;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ProfileVaultClaimTest {

	static {
		TestAndroidKeyStore.register();
	}

	private Context app;
	private File deviceVault;
	private File deviceXmr;
	private File deviceStickers;

	@Before
	public void setUp() throws Exception {
		app = RuntimeEnvironment.getApplication();
		com.professor.zerion.android.AppModule.getAndroidComponent(app)
				.accountManager();
		deviceVault = new File(app.getNoBackupFilesDir(), "vault");
		deviceXmr = new File(app.getNoBackupFilesDir(), "xmr");
		deviceStickers = new File(app.getFilesDir(), "stickers");
		assertTrue(deviceVault.mkdirs());
		Files.write(new File(deviceVault, "vault.header").toPath(),
				new byte[] {1});
		assertTrue(deviceXmr.mkdirs());
		Files.write(new File(deviceXmr, "w.keys").toPath(), new byte[] {2});
		assertTrue(deviceStickers.mkdirs());
		Files.write(new File(deviceStickers, "s1.bin").toPath(),
				new byte[] {3});
		addProfile(app, "default");
		addProfile(app, "b7c1");
	}

	@After
	public void tearDown() {
		IoUtils.deleteFileOrDir(new File(app.getFilesDir(), "profiles"));
		IoUtils.deleteFileOrDir(deviceVault);
		IoUtils.deleteFileOrDir(deviceXmr);
		IoUtils.deleteFileOrDir(deviceStickers);
		new File(app.getNoBackupFilesDir(), ProfileStorage.DEVICE_VAULT_CLAIM)
				.delete();
	}

	private static File walletXmrDir(ProfileStorage storage) {
		Context c = storage.walletContext();
		return new File(c.getApplicationContext().getNoBackupFilesDir(), "xmr");
	}

	@Test
	public void theClaimIsOfferedOnceAfterTheUnlockAndNotBefore() {
		ProfileStorage hidden = signedIn(app, "b7c1");
		assertFalse("nothing is offered before the vault is unlocked",
				hidden.offersSharedVaultClaim());
		assertFalse(hidden.claimSharedVault());

		hidden.vaultLocation().onUnlocked();

		assertTrue(hidden.offersSharedVaultClaim());
		hidden.sharedVaultClaimOffered();
		assertFalse("asked once per session", hidden.offersSharedVaultClaim());
		assertTrue(hidden.claimSharedVault());
	}

	@Test
	public void aConfirmedClaimMovesTheVaultWalletsAndStickersAtTheNextSignIn()
			throws Exception {
		ProfileStorage decoy = signedIn(app, "default");
		assertTrue(decoy.vaultIsShared());
		assertEquals("stickers wait for the claim", 0,
				decoy.stickerDir().list().length);
		assertTrue(deviceStickers.exists());

		ProfileStorage hidden = signedIn(app, "b7c1");
		hidden.vaultLocation().onUnlocked();
		assertTrue(hidden.claimSharedVault());

		ProfileStorage hiddenAgain = signedIn(app, "b7c1");
		File own = new File(profileDir(app, "b7c1"), "vault");
		assertEquals(own, hiddenAgain.vaultLocation().directory());
		assertTrue(new File(own, "vault.header").exists());
		assertEquals(VaultKeystore.LEGACY_KEY_ALIAS,
				hiddenAgain.vaultLocation().keyAlias());
		assertTrue(new File(walletXmrDir(hiddenAgain), "w.keys").exists());
		assertTrue(new File(hiddenAgain.stickerDir(), "s1.bin").exists());
		assertFalse(deviceStickers.exists());

		ProfileStorage decoyAgain = signedIn(app, "default");
		assertFalse("the other profile no longer sees it",
				decoyAgain.vaultIsShared());
		File decoyOwn = decoyAgain.vaultLocation().directory();
		assertEquals(new File(profileDir(app, "default"), "vault"), decoyOwn);
		assertFalse(new File(decoyOwn, "vault.header").exists());
		assertNotEquals(VaultKeystore.LEGACY_KEY_ALIAS,
				decoyAgain.vaultLocation().keyAlias());
		assertEquals(0, decoyAgain.stickerDir().list().length);
		assertFalse("nothing is offered once the vault is a profile's own",
				decoyAgain.offersSharedVaultClaim());
	}

	@Test
	public void aClaimOfAProfileThatIsGoneIsIgnoredAndRemoved()
			throws Exception {
		addProfile(app, "c9d0");
		ProfileStorage third = signedIn(app, "c9d0");
		third.vaultLocation().onUnlocked();
		assertTrue(third.claimSharedVault());
		IoUtils.deleteFileOrDir(profileDir(app, "c9d0"));

		assertTrue(signedIn(app, "default").vaultIsShared());
		assertFalse("the stale claim is removed", new File(
				app.getNoBackupFilesDir(), ProfileStorage.DEVICE_VAULT_CLAIM)
				.exists());
		ProfileStorage hidden = signedIn(app, "b7c1");
		assertTrue(hidden.vaultIsShared());
		hidden.vaultLocation().onUnlocked();
		assertTrue("another profile may claim it now",
				hidden.offersSharedVaultClaim());
	}

	@Test
	public void deletingTheClaimingProfileWithdrawsItsClaim() {
		ProfileStorage hidden = signedIn(app, "b7c1");
		hidden.vaultLocation().onUnlocked();
		assertTrue(hidden.claimSharedVault());

		hidden.forgetClaimOf("b7c1");

		assertFalse(new File(app.getNoBackupFilesDir(),
				ProfileStorage.DEVICE_VAULT_CLAIM).exists());
		assertTrue(signedIn(app, "default").vaultIsShared());
	}
}
