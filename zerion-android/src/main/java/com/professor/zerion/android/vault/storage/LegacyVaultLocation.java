package com.professor.zerion.android.vault.storage;

import android.content.Context;

import com.professor.zerion.android.vault.crypto.VaultKeystore;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.util.Collections;
import java.util.List;

@NotNullByDefault
public final class LegacyVaultLocation implements VaultLocation {

	private final File dir;

	public LegacyVaultLocation(Context context) {
		this.dir = directoryOf(context);
	}

	public static File directoryOf(Context context) {
		return new File(context.getNoBackupFilesDir(), "vault");
	}

	@Override
	public boolean isAvailable() {
		return true;
	}

	@Override
	public File directory() {
		return dir;
	}

	@Override
	public String keyAlias() {
		return VaultKeystore.LEGACY_KEY_ALIAS;
	}

	@Override
	public List<File> allVaultDirectories() {
		return Collections.singletonList(dir);
	}

	@Override
	public void onUnlocked() {
	}
}
