package com.professor.zerion.android;

import org.briarproject.bramble.account.ProfileManager;
import org.briarproject.bramble.api.crypto.KeyStrengthener;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;

import javax.annotation.Nullable;

@NotNullByDefault
class AndroidDatabaseConfig implements DatabaseConfig {

	private final ProfileManager profileManager;
	@Nullable
	private final KeyStrengthener keyStrengthener;

	AndroidDatabaseConfig(ProfileManager profileManager,
			@Nullable KeyStrengthener keyStrengthener) {
		this.profileManager = profileManager;
		this.keyStrengthener = keyStrengthener;
	}

	@Override
	public File getDatabaseDirectory() {
		return profileManager.getActiveDbDir();
	}

	@Override
	public File getDatabaseKeyDirectory() {
		return profileManager.getActiveKeyDir();
	}

	@Nullable
	@Override
	public KeyStrengthener getKeyStrengthener() {
		return keyStrengthener;
	}
}
