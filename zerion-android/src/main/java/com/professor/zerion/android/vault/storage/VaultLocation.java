package com.professor.zerion.android.vault.storage;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.util.List;

@NotNullByDefault
public interface VaultLocation {

	boolean isAvailable();

	File directory();

	String keyAlias();

	List<File> allVaultDirectories();

	void onUnlocked();
}
