package com.professor.zerion.android.settings;

import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static org.briarproject.bramble.api.plugin.Plugin.PREF_PLUGIN_ENABLE;
import static org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_PREF_TOR_MOBILE;
import static org.briarproject.bramble.api.plugin.TorConstants.DEFAULT_PREF_TOR_NETWORK;
import static org.briarproject.bramble.api.plugin.TorConstants.PREF_TOR_MOBILE;
import static org.briarproject.bramble.api.plugin.TorConstants.PREF_TOR_NETWORK;
import static com.professor.zerion.android.settings.ConnectionsFragment.PREF_KEY_ORBOT_ENABLED;
import static com.professor.zerion.android.settings.ConnectionsFragment.PREF_KEY_ORBOT_HOST;
import static com.professor.zerion.android.settings.ConnectionsFragment.PREF_KEY_ORBOT_PORT;
import static com.professor.zerion.android.settings.SettingsViewModel.BT_NAMESPACE;
import static com.professor.zerion.android.settings.SettingsViewModel.TOR_NAMESPACE;
import static com.professor.zerion.android.settings.SettingsViewModel.WIFI_NAMESPACE;

@NotNullByDefault
class ConnectionsManager {

	private static final String DEFAULT_ORBOT_HOST = "127.0.0.1";
	private static final int DEFAULT_ORBOT_PORT = 9050;

	final ConnectionsStore btStore;
	final ConnectionsStore wifiStore;
	final ConnectionsStore torStore;

	private final MutableLiveData<Boolean> btEnabled = new MutableLiveData<>();
	private final MutableLiveData<Boolean> wifiEnabled = new MutableLiveData<>();
	private final MutableLiveData<Boolean> torEnabled = new MutableLiveData<>();
	private final MutableLiveData<String> torNetwork = new MutableLiveData<>();
	private final MutableLiveData<Boolean> torMobile = new MutableLiveData<>();
	private final MutableLiveData<Boolean> orbotEnabled = new MutableLiveData<>();
	private final MutableLiveData<String> orbotHost = new MutableLiveData<>();
	private final MutableLiveData<Integer> orbotPort = new MutableLiveData<>();

	ConnectionsManager(SettingsManager settingsManager,
			Executor dbExecutor) {
		btStore = new ConnectionsStore(settingsManager, dbExecutor, BT_NAMESPACE);
		wifiStore = new ConnectionsStore(settingsManager, dbExecutor, WIFI_NAMESPACE);
		torStore = new ConnectionsStore(settingsManager, dbExecutor, TOR_NAMESPACE);
	}

	void updateBtSetting(Settings btSettings) {
		btEnabled.postValue(false);
	}

	void updateWifiSettings(Settings wifiSettings) {
		wifiEnabled.postValue(false);
	}

	void updateTorSettings(Settings settings) {
		torEnabled.postValue(settings.getBoolean(PREF_PLUGIN_ENABLE,
				TorConstants.DEFAULT_PREF_PLUGIN_ENABLE));

		int torNetworkSetting = settings.getInt(PREF_TOR_NETWORK,
				DEFAULT_PREF_TOR_NETWORK);
		torNetwork.postValue(Integer.toString(torNetworkSetting));

		torMobile.postValue(settings.getBoolean(PREF_TOR_MOBILE,
				DEFAULT_PREF_TOR_MOBILE));
		orbotEnabled.postValue(settings.getBoolean(PREF_KEY_ORBOT_ENABLED, false));
		String host = settings.get(PREF_KEY_ORBOT_HOST);
		orbotHost.postValue(host != null ? host : DEFAULT_ORBOT_HOST);
		orbotPort.postValue(settings.getInt(PREF_KEY_ORBOT_PORT, DEFAULT_ORBOT_PORT));
	}

	LiveData<Boolean> btEnabled() {
		return btEnabled;
	}

	LiveData<Boolean> wifiEnabled() {
		return wifiEnabled;
	}

	LiveData<Boolean> torEnabled() {
		return torEnabled;
	}

	LiveData<String> torNetwork() {
		return torNetwork;
	}

	LiveData<Boolean> torMobile() {
		return torMobile;
	}

	LiveData<Boolean> orbotEnabled() {
		return orbotEnabled;
	}

	LiveData<String> orbotHost() {
		return orbotHost;
	}

	LiveData<Integer> orbotPort() {
		return orbotPort;
	}

}
