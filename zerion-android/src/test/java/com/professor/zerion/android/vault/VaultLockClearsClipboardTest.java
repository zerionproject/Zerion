package com.professor.zerion.android.vault;

import android.app.Application;
import android.content.ClipboardManager;
import android.content.Context;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.ClearableClipboardShadow;
import com.professor.zerion.android.util.SecureClipboard;
import com.professor.zerion.android.vault.storage.VaultLocation;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.core.util.IoUtils.deleteFileOrDir;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = {ClearableClipboardShadow.class,
		DirectorySyncShadow.class})
public class VaultLockClearsClipboardTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Application app = RuntimeEnvironment.getApplication();
	private File dir;

	@After
	public void tearDown() {
		if (dir != null) deleteFileOrDir(dir);
	}

	@Test
	public void anUntimedVaultCopyIsClearedWhenTheVaultLocks()
			throws Exception {
		dir = Files.createTempDirectory("vault-clip").toFile();
		File vaultDir = new File(dir, "vault");
		VaultManager vault = new VaultManager(app, new VaultLocation() {
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
				return "zerion_vault_master_key_clip";
			}

			@Override
			public List<File> allVaultDirectories() {
				return Collections.singletonList(vaultDir);
			}

			@Override
			public void onUnlocked() {
			}
		});
		SecureClipboard.copySensitive(app, "Password", "copied-secret", 0L);
		vault.lockVault();
		ClipboardManager cm = (ClipboardManager)
				app.getSystemService(Context.CLIPBOARD_SERVICE);
		assertEquals("clipboard empty after the vault locked",
				"clipboard " + (cm.hasPrimaryClip() ? "holds the copy"
						: "empty") + " after the vault locked");
	}
}
