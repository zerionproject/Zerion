package com.professor.zerion.android.settings;

import android.app.Application;

import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static org.zerionproject.core.api.plugin.Plugin.PREF_PLUGIN_ENABLE;
import static org.zerionproject.core.api.plugin.TorConstants.DEFAULT_PREF_TOR_NETWORK;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_CUSTOM_BRIDGES;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK;
import static com.professor.zerion.android.settings.SettingsViewModel.TOR_NAMESPACE;

@NotNullByDefault
class ConnectionsManager {

	final ConnectionsStore torStore;

	private final MutableLiveData<Boolean> torEnabled = new MutableLiveData<>();
	private final MutableLiveData<String> torNetwork = new MutableLiveData<>();
	private final MutableLiveData<String> customBridges = new MutableLiveData<>();
	private final MutableLiveData<Integer> helperTransports =
			new MutableLiveData<>();
	private final MutableLiveData<String> helperTransportsCountry =
			new MutableLiveData<>();
	private final MutableLiveData<String> debugCountry =
			new MutableLiveData<>();

	ConnectionsManager(Application app, SettingsManager settingsManager,
			Executor dbExecutor) {
		torStore = new ConnectionsStore(app, settingsManager, dbExecutor,
				TOR_NAMESPACE);
	}

	void updateTorSettings(Settings settings) {
		torEnabled.postValue(settings.getBoolean(PREF_PLUGIN_ENABLE,
				TorConstants.DEFAULT_PREF_PLUGIN_ENABLE));

		int torNetworkSetting = settings.getInt(PREF_TOR_NETWORK,
				DEFAULT_PREF_TOR_NETWORK);
		torNetwork.postValue(Integer.toString(torNetworkSetting));

		customBridges.postValue(settings.get(PREF_TOR_CUSTOM_BRIDGES));
		helperTransports.postValue(settings.getInt(
				TorConstants.PREF_TOR_HELPER_TRANSPORTS,
				TorConstants.HELPER_TRANSPORTS_ASK));
		helperTransportsCountry.postValue(settings.get(
				TorConstants.PREF_TOR_HELPER_TRANSPORTS_COUNTRY));
		debugCountry.postValue(
				settings.get(TorConstants.PREF_TOR_DEBUG_COUNTRY));
	}

	LiveData<Integer> helperTransports() {
		return helperTransports;
	}

	LiveData<String> helperTransportsCountry() {
		return helperTransportsCountry;
	}

	LiveData<String> debugCountry() {
		return debugCountry;
	}

	LiveData<Boolean> torEnabled() {
		return torEnabled;
	}

	LiveData<String> torNetwork() {
		return torNetwork;
	}

	LiveData<String> customBridges() {
		return customBridges;
	}

}
