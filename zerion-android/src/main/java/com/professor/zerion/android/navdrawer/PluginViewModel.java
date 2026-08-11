package com.professor.zerion.android.navdrawer;

import android.app.Application;

import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.network.NetworkManager;
import org.zerionproject.core.api.network.NetworkStatus;
import org.zerionproject.core.api.network.event.NetworkStatusEvent;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.Plugin.State;
import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.event.TransportStateEvent;
import org.zerionproject.core.api.properties.TransportProperties;
import org.zerionproject.core.api.properties.TransportPropertyManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.settings.event.SettingsUpdatedEvent;
import org.zerionproject.core.api.system.AndroidExecutor;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import com.professor.zerion.android.viewmodel.DbViewModel;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import static org.zerionproject.core.api.plugin.Plugin.PREF_PLUGIN_ENABLE;
import static org.zerionproject.core.api.plugin.Plugin.State.STARTING_STOPPING;

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

	private final MutableLiveData<State> i2pPluginState =
			new MutableLiveData<>();

	private final MutableLiveData<Boolean> i2pEnabledSetting =
			new MutableLiveData<>(false);

	private final MutableLiveData<NetworkStatus> networkStatus =
			new MutableLiveData<>();

	private final MutableLiveData<String> torLocalOnion =
			new MutableLiveData<>();

	private final MutableLiveData<B4OnionRotation.RotationPhase>
			rotationPhase = new MutableLiveData<>(
			B4OnionRotation.RotationPhase.IDLE);

	private final MutableLiveData<String> rotationPendingOnion =
			new MutableLiveData<>();

	private final B4OnionRotation b4OnionRotation;

	@Inject
	PluginViewModel(Application app, @DatabaseExecutor Executor dbExecutor,
			LifecycleManager lifecycleManager, TransactionManager db,
			AndroidExecutor androidExecutor, SettingsManager settingsManager,
			PluginManager pluginManager, EventBus eventBus,
			NetworkManager networkManager,
			TransportPropertyManager transportPropertyManager,
			B4OnionRotation b4OnionRotation) {
		super(app, dbExecutor, lifecycleManager, db, androidExecutor);
		this.app = app;
		this.settingsManager = settingsManager;
		this.pluginManager = pluginManager;
		this.eventBus = eventBus;
		this.transportPropertyManager = transportPropertyManager;
		this.b4OnionRotation = b4OnionRotation;
		eventBus.addListener(this);
		networkStatus.setValue(networkManager.getNetworkStatus());
		torPluginState.setValue(getTransportState(TorConstants.ID));
		i2pPluginState.setValue(getTransportState(I2pConstants.ID));
		loadSettings();
		loadLocalOnion();
		loadRotationState();
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
			} else if (s.getNamespace().equals(I2pConstants.ID.getString())) {
				boolean enable = s.getSettings().getBoolean(PREF_PLUGIN_ENABLE,
						I2pConstants.DEFAULT_PREF_PLUGIN_ENABLE);
				i2pEnabledSetting.setValue(enable);
			}
		} else if (e instanceof TransportStateEvent) {
			TransportStateEvent t = (TransportStateEvent) e;
			if (t.getTransportId().equals(TorConstants.ID)) {
				torPluginState.postValue(t.getState());
				if (t.getState() == State.ACTIVE) {
					loadLocalOnion();
				}
			} else if (t.getTransportId().equals(I2pConstants.ID)) {
				i2pPluginState.postValue(t.getState());
			}
		}
	}

	LiveData<String> getLocalOnion() {
		return torLocalOnion;
	}

	LiveData<B4OnionRotation.RotationPhase> getRotationPhase() {
		return rotationPhase;
	}

	LiveData<String> getRotationPendingOnion() {
		return rotationPendingOnion;
	}

	public void refreshTorState() {
		loadLocalOnion();
		loadRotationState();
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

	private void loadRotationState() {
		runOnDbThread(() -> {
			try {
				B4OnionRotation.RotationPhase phase =
						b4OnionRotation.getPhase();
				rotationPhase.postValue(phase);
				if (phase == B4OnionRotation.RotationPhase.ANNOUNCING) {
					rotationPendingOnion.postValue(
							b4OnionRotation.getAliceNextOnion());
				} else {
					rotationPendingOnion.postValue(null);
				}
			} catch (DbException e) {
				handleException(e);
			}
		});
	}

	LiveData<State> getPluginState(TransportId id) {
		if (id.equals(TorConstants.ID)) return torPluginState;
		if (id.equals(I2pConstants.ID)) return i2pPluginState;
		throw new IllegalArgumentException("Unknown transport: " + id);
	}

	public LiveData<Boolean> getPluginEnabledSetting(TransportId id) {
		if (id.equals(TorConstants.ID)) return torEnabledSetting;
		if (id.equals(I2pConstants.ID)) return i2pEnabledSetting;
		throw new IllegalArgumentException("Unknown transport: " + id);
	}

	LiveData<NetworkStatus> getNetworkStatus() {
		return networkStatus;
	}

	int getReasonsTorDisabled() {
		Plugin plugin = pluginManager.getPlugin(TorConstants.ID);
		return plugin == null ? 0 : plugin.getReasonsDisabled();
	}

	boolean isPluginRegistered(TransportId id) {
		return pluginManager.getPlugin(id) != null;
	}

	public void enableTransport(TransportId id, boolean enable) {
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
				boolean i2p = isPluginEnabled(I2pConstants.ID,
						I2pConstants.DEFAULT_PREF_PLUGIN_ENABLE);
				i2pEnabledSetting.postValue(i2p);
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
