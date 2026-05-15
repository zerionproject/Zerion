package com.professor.zerion.android.settings;

import android.app.Application;

import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import androidx.annotation.Nullable;

import static org.briarproject.bramble.api.plugin.Plugin.PREF_PLUGIN_ENABLE;
import static org.briarproject.bramble.api.plugin.TorConstants.PREF_TOR_MOBILE;
import static org.briarproject.bramble.api.plugin.TorConstants.PREF_TOR_NETWORK;
import static com.professor.zerion.android.settings.ConnectionsFragment.PREF_KEY_ORBOT_ENABLED;
import static com.professor.zerion.android.settings.ConnectionsFragment.PREF_KEY_ORBOT_HOST;
import static com.professor.zerion.android.settings.ConnectionsFragment.PREF_KEY_ORBOT_PORT;
import static com.professor.zerion.android.settings.ConnectionsFragment.PREF_KEY_TOR_MOBILE_DATA;
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
	public void putBoolean(String key, boolean value) {
		String newKey;
		switch (key) {
			case PREF_KEY_TOR_MOBILE_DATA:
			case PREF_TOR_MOBILE:
				newKey = PREF_TOR_MOBILE;
				break;
			case PREF_KEY_ORBOT_ENABLED:
				newKey = PREF_KEY_ORBOT_ENABLED;
				break;
			default:
				newKey = key;
				break;
		}
		super.putBoolean(newKey, value);
	}

	@Override
	public void putString(String key, @Nullable String value) {
		String newKey;
		switch (key) {
			case PREF_KEY_TOR_NETWORK:
			case PREF_TOR_NETWORK:
				newKey = PREF_TOR_NETWORK;
				break;
			case PREF_KEY_ORBOT_HOST:
				newKey = PREF_KEY_ORBOT_HOST;
				break;
			default:
				newKey = key;
				break;
		}
		super.putString(newKey, value);
	}

	@Override
	public void putInt(String key, int value) {
		String newKey;
		switch (key) {
			case PREF_KEY_ORBOT_PORT:
				newKey = PREF_KEY_ORBOT_PORT;
				break;
			default:
				newKey = key;
				break;
		}
		super.putInt(newKey, value);
	}

}
