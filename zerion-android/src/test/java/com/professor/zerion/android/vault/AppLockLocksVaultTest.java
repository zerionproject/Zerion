package com.professor.zerion.android.vault;

import android.app.Application;

import com.professor.zerion.android.api.AndroidNotificationManager;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.ClearableClipboardShadow;
import com.professor.zerion.android.vault.crypto.Argon2;
import com.professor.zerion.android.vault.storage.VaultLocation;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.settings.SettingsManager;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.zerionproject.core.util.IoUtils.deleteFileOrDir;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class AppLockLocksVaultTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Application app = RuntimeEnvironment.getApplication();
	private File dir;

	@After
	public void tearDown() {
		if (dir != null) deleteFileOrDir(dir);
	}

	private static byte[] fakeDerive(char[] password, byte[] salt,
			Argon2.Argon2Params params) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(salt, "HmacSHA256"));
			mac.update(new String(password).getBytes(StandardCharsets.UTF_8));
			return mac.doFinal(params.toBytes());
		} catch (Exception e) {
			throw new AssertionError(e);
		}
	}

	@Test
	public void lockingTheAppLocksTheVault() throws Exception {
		dir = Files.createTempDirectory("app-lock-vault").toFile();
		File vaultDir = new File(dir, "vault");
		VaultLocation location = new VaultLocation() {
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
				return "zerion_vault_master_key_applock";
			}

			@Override
			public List<File> allVaultDirectories() {
				return Collections.singletonList(vaultDir);
			}

			@Override
			public void onUnlocked() {
			}
		};
		VaultManager vault = new VaultManager(app, location,
				AppLockLocksVaultTest::fakeDerive);
		vault.createVault("vault password 1!Zz".toCharArray());
		boolean before = vault.isUnlocked();

		new com.professor.zerion.android.account.LockManagerForTests()
				.create(app, mock(SettingsManager.class),
						mock(AndroidNotificationManager.class), vault)
				.setLocked(true);
		assertEquals("unlocked before the app lock, locked after",
				(before ? "unlocked" : "locked")
						+ " before the app lock, "
						+ (vault.isUnlocked() ? "unlocked" : "locked")
						+ " after");
	}
}
