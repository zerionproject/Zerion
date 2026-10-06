package com.professor.zerion.android.backup;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;

import javax.inject.Inject;

import static org.zerionproject.core.api.plugin.TorConstants.PREF_ACCOUNT_MOVED;

@NotNullByDefault
public class AccountRetirement {

	private final SettingsManager settingsManager;

	@Inject
	public AccountRetirement(SettingsManager settingsManager) {
		this.settingsManager = settingsManager;
	}

	public void retire() throws DbException {
		Settings s = new Settings();
		s.putBoolean(PREF_ACCOUNT_MOVED, true);
		settingsManager.mergeSettings(s, TorConstants.ID.getString());
	}

	public boolean isRetired() throws DbException {
		return settingsManager.getSettings(TorConstants.ID.getString())
				.getBoolean(PREF_ACCOUNT_MOVED, false);
	}
}
