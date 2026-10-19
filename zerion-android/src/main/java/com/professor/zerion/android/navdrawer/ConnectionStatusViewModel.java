package com.professor.zerion.android.navdrawer;

import android.os.Handler;
import android.os.Looper;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.network.NetworkManager;
import org.zerionproject.core.api.network.event.NetworkStatusEvent;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TorEntryRelayStatus;
import org.zerionproject.core.api.plugin.event.TorBootstrapEvent;
import org.zerionproject.core.api.plugin.event.TorEntryRelayEvent;
import org.zerionproject.core.api.plugin.event.TransportStateEvent;
import org.zerionproject.core.api.settings.event.SettingsUpdatedEvent;

import javax.inject.Inject;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModel;

@NotNullByDefault
public class ConnectionStatusViewModel extends ViewModel
		implements EventListener {

	private final EventBus eventBus;
	private final PluginManager pluginManager;
	private final TorPublishTracker tracker;
	private final Handler main = new Handler(Looper.getMainLooper());
	private final Runnable recompute = this::refresh;
	private final MutableLiveData<ConnectionChip.State> state =
			new MutableLiveData<>();
	private final Observer<Object> trackerChanged = v -> refresh();
	private volatile boolean internet;
	private volatile boolean entryRelayFailing;
	private volatile int bootstrap = 0;

	@Inject
	ConnectionStatusViewModel(EventBus eventBus, PluginManager pluginManager,
			NetworkManager networkManager, TorPublishTracker tracker) {
		this.eventBus = eventBus;
		this.pluginManager = pluginManager;
		this.tracker = tracker;
		internet = networkManager.getNetworkStatus().isConnected();
		Plugin tor = pluginManager.getPlugin(TorConstants.ID);
		entryRelayFailing = tor instanceof TorEntryRelayStatus
				&& ((TorEntryRelayStatus) tor).isEntryRelayFailing();
		eventBus.addListener(this);
		tracker.getPublished().observeForever(trackerChanged);
		tracker.getClockSkewSeconds().observeForever(trackerChanged);
		refresh();
	}

	LiveData<ConnectionChip.State> getState() {
		return state;
	}

	@Override
	protected void onCleared() {
		eventBus.removeListener(this);
		tracker.getPublished().removeObserver(trackerChanged);
		tracker.getClockSkewSeconds().removeObserver(trackerChanged);
		main.removeCallbacks(recompute);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof NetworkStatusEvent) {
			internet = ((NetworkStatusEvent) e).getStatus().isConnected();
		} else if (e instanceof TransportStateEvent) {
			TransportStateEvent t = (TransportStateEvent) e;
			if (!t.getTransportId().equals(TorConstants.ID)) return;
			if (t.getState() == Plugin.State.STARTING_STOPPING) bootstrap = 0;
		} else if (e instanceof TorBootstrapEvent) {
			bootstrap = ((TorBootstrapEvent) e).getPercentage();
		} else if (e instanceof TorEntryRelayEvent) {
			entryRelayFailing = ((TorEntryRelayEvent) e).isFailing();
		} else if (!(e instanceof SettingsUpdatedEvent)) {
			return;
		}
		main.removeCallbacks(recompute);
		main.post(recompute);
	}

	@UiThread
	void refresh() {
		Plugin tor = pluginManager.getPlugin(TorConstants.ID);
		Long skew = tracker.getClockSkewSeconds().getValue();
		ConnectionChip.State next = ConnectionChip.decide(
				pluginManager.isOfflineMode(),
				pluginManager.isConnectionsPaused(),
				tor == null ? null : tor.getState(), internet,
				tracker.isPublished(), skew != null && skew != 0,
				entryRelayFailing, bootstrap);
		if (next != state.getValue()) state.setValue(next);
	}
}
