package com.professor.zerion.android.settings;

import android.app.Application;

import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import androidx.annotation.Nullable;

import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK;
import static com.professor.zerion.android.settings.ConnectionsFragment.PREF_KEY_TOR_NETWORK;

@NotNullByDefault
class ConnectionsStore extends SettingsStore {

	ConnectionsStore(
			Application app,
			SettingsManager settingsManager,
			Executor dbExecutor,
			String namespace) {
		super(app, settingsManager, dbExecutor, namespace);
	}

	@Override
	public void putString(String key, @Nullable String value) {
		String newKey;
		switch (key) {
			case PREF_KEY_TOR_NETWORK:
			case PREF_TOR_NETWORK:
				newKey = PREF_TOR_NETWORK;
				break;
			default:
				newKey = key;
				break;
		}
		super.putString(newKey, value);
	}

}
