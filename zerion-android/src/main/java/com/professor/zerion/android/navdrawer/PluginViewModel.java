package com.professor.zerion.android.navdrawer;

import android.app.Application;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.network.NetworkManager;
import org.briarproject.bramble.api.network.NetworkStatus;
import org.briarproject.bramble.api.network.event.NetworkStatusEvent;
import org.briarproject.bramble.api.plugin.Plugin;
import org.briarproject.bramble.api.plugin.Plugin.State;
import org.briarproject.bramble.api.plugin.PluginManager;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.event.TransportStateEvent;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.bramble.api.properties.TransportPropertyManager;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.settings.event.SettingsUpdatedEvent;
import org.briarproject.bramble.api.system.AndroidExecutor;
import com.professor.zerion.android.viewmodel.DbViewModel;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static org.briarproject.bramble.api.plugin.Plugin.PREF_PLUGIN_ENABLE;
import static org.briarproject.bramble.api.plugin.Plugin.State.STARTING_STOPPING;

@NotNullByDefault
public class PluginViewModel extends DbViewModel implements EventListener {

	private final Application app;
	private final SettingsManager settingsManager;
	private final PluginManager pluginManager;
	private final EventBus eventBus;
	private final TransportPropertyManager transportPropertyManager;

	private final MutableLiveData<State> torPluginState =
			new MutableLiveData<>();

	private final MutableLiveData<Boolean> torEnabledSetting =
			new MutableLiveData<>(false);

	private final MutableLiveData<NetworkStatus> networkStatus =
			new MutableLiveData<>();

	private final MutableLiveData<String> torLocalOnion =
			new MutableLiveData<>();

	@Inject
	PluginViewModel(Application app, @DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, TransactionManager db,
			AndroidExecutor androidExecutor, SettingsManager settingsManager,
			PluginManager pluginManager, EventBus eventBus,
			NetworkManager networkManager,
			TransportPropertyManager transportPropertyManager) {
		super(app, dbExecutor, lifecycleManager, db, androidExecutor);
		this.app = app;
		this.settingsManager = settingsManager;
		this.pluginManager = pluginManager;
		this.eventBus = eventBus;
		this.transportPropertyManager = transportPropertyManager;
		eventBus.addListener(this);
		networkStatus.setValue(networkManager.getNetworkStatus());
		torPluginState.setValue(getTransportState(TorConstants.ID));
		loadSettings();
		loadLocalOnion();
	}

	@Override
	protected void onCleared() {
		eventBus.removeListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof NetworkStatusEvent) {
			networkStatus.setValue(((NetworkStatusEvent) e).getStatus());
		} else if (e instanceof SettingsUpdatedEvent) {
			SettingsUpdatedEvent s = (SettingsUpdatedEvent) e;
			if (s.getNamespace().equals(TorConstants.ID.getString())) {
				boolean enable = s.getSettings().getBoolean(PREF_PLUGIN_ENABLE,
						TorConstants.DEFAULT_PREF_PLUGIN_ENABLE);
				torEnabledSetting.setValue(enable);
			}
		} else if (e instanceof TransportStateEvent) {
			TransportStateEvent t = (TransportStateEvent) e;
			if (t.getTransportId().equals(TorConstants.ID)) {
				torPluginState.postValue(t.getState());
				if (t.getState() == State.ACTIVE) {
					loadLocalOnion();
				}
			}
		}
	}

	LiveData<String> getLocalOnion() {
		return torLocalOnion;
	}

	private void loadLocalOnion() {
		runOnDbThread(() -> {
			try {
				TransportProperties props =
						transportPropertyManager.getLocalProperties(
								TorConstants.ID);
				String onion = props == null ? null
						: props.get(TorConstants.PROP_ONION_V3);
				if (onion != null && !onion.isEmpty()) {
					torLocalOnion.postValue(onion);
				}
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveData<State> getPluginState(TransportId id) {
		if (id.equals(TorConstants.ID)) return torPluginState;
		throw new IllegalArgumentException("Unknown transport: " + id);
	}

	LiveData<Boolean> getPluginEnabledSetting(TransportId id) {
		if (id.equals(TorConstants.ID)) return torEnabledSetting;
		throw new IllegalArgumentException("Unknown transport: " + id);
	}

	LiveData<NetworkStatus> getNetworkStatus() {
		return networkStatus;
	}

	int getReasonsTorDisabled() {
		Plugin plugin = pluginManager.getPlugin(TorConstants.ID);
		return plugin == null ? 0 : plugin.getReasonsDisabled();
	}

	void enableTransport(TransportId id, boolean enable) {
		Settings s = new Settings();
		s.putBoolean(PREF_PLUGIN_ENABLE, enable);
		mergeSettings(s, id.getString());
	}

	private void loadSettings() {
		runOnDbThread(() -> {
			try {
				boolean tor = isPluginEnabled(TorConstants.ID,
						TorConstants.DEFAULT_PREF_PLUGIN_ENABLE);
				torEnabledSetting.postValue(tor);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	private boolean isPluginEnabled(TransportId id, boolean defaultValue)
			throws DbException {
		Settings s = settingsManager.getSettings(id.getString());
		return s.getBoolean(PREF_PLUGIN_ENABLE, defaultValue);
	}

	private State getTransportState(TransportId id) {
		Plugin plugin = pluginManager.getPlugin(id);
		return plugin == null ? STARTING_STOPPING : plugin.getState();
	}

	private void mergeSettings(Settings s, String namespace) {
		runOnDbThread(() -> {
			try {
				settingsManager.mergeSettings(s, namespace);
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

}
