package org.zerionproject.core.settings;

import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.settings.SettingsManager;

public final class SettingsManagerOverDatabase {

	private SettingsManagerOverDatabase() {
	}

	public static SettingsManager create(DatabaseComponent db) {
		return new SettingsManagerImpl(db);
	}
}
