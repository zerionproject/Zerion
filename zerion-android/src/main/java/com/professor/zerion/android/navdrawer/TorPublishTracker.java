package com.professor.zerion.android.navdrawer;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.Plugin.State;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.api.plugin.TorClockSkewStatus;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.event.ConnectionOpenedEvent;
import org.zerionproject.core.api.plugin.event.TorClockSkewEvent;
import org.zerionproject.core.api.plugin.event.TorOnionPublishedEvent;
import org.zerionproject.core.api.plugin.event.TransportStateEvent;

import javax.inject.Inject;
import javax.inject.Singleton;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

@Singleton
@NotNullByDefault
public class TorPublishTracker implements EventListener {

	static final long PUBLISH_GRACE_MS = 60_000;

	private final PluginManager pluginManager;
	private final ConnectionRegistry connectionRegistry;
	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final TorPublishState state =
			new TorPublishState(PUBLISH_GRACE_MS);
	private final MutableLiveData<Boolean> published =
			new MutableLiveData<>(false);
	private final MutableLiveData<Long> clockSkewSeconds =
			new MutableLiveData<>(0L);
	private final Runnable check = this::runCheck;

	@Inject
	public TorPublishTracker(EventBus eventBus, PluginManager pluginManager,
			ConnectionRegistry connectionRegistry) {
		this.pluginManager = pluginManager;
		this.connectionRegistry = connectionRegistry;
		eventBus.addListener(this);
		if (Looper.myLooper() == Looper.getMainLooper()) catchUp();
		else mainHandler.post(this::catchUp);
	}

	LiveData<Boolean> getPublished() {
		return published;
	}

	LiveData<Long> getClockSkewSeconds() {
		return clockSkewSeconds;
	}

	void refresh() {
		if (hasTorContacts()) onProof();
		else runCheck();
	}

	void onRestartRequested() {
		schedule(state.onInactive(true, now()));
		show();
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof TransportStateEvent) {
			TransportStateEvent t = (TransportStateEvent) e;
			if (!t.getTransportId().equals(TorConstants.ID)) return;
			State torState = t.getState();
			if (torState == State.ACTIVE) {
				mainHandler.post(() -> {
					schedule(state.onActive(now(), currentSkew()));
					show();
				});
			} else {
				boolean fullStop = torState == State.DISABLED
						|| pluginManager.isOfflineMode();
				mainHandler.post(() -> {
					schedule(state.onInactive(fullStop, now()));
					show();
				});
			}
		} else if (e instanceof TorClockSkewEvent) {
			long skew = ((TorClockSkewEvent) e).getSkewSeconds();
			mainHandler.post(() -> {
				schedule(state.onClockSkew(skew));
				show();
			});
		} else if (e instanceof TorOnionPublishedEvent) {
			mainHandler.post(this::onProof);
		} else if (e instanceof ConnectionOpenedEvent) {
			ConnectionOpenedEvent c = (ConnectionOpenedEvent) e;
			if (c.getTransportId().equals(TorConstants.ID) && c.isIncoming()) {
				mainHandler.post(this::onProof);
			}
		}
	}

	private void catchUp() {
		if (hasTorContacts()) state.onPublishProof();
		Plugin p = pluginManager.getPlugin(TorConstants.ID);
		if (p != null && p.getState() == State.ACTIVE) {
			schedule(state.onActive(now(), currentSkew()));
		}
		show();
	}

	private boolean hasTorContacts() {
		return !connectionRegistry.getConnectedContacts(TorConstants.ID)
				.isEmpty();
	}

	private void onProof() {
		schedule(TorPublishState.NO_CHECK);
		state.onPublishProof();
		show();
	}

	private void runCheck() {
		schedule(state.onCheck(now(), currentSkew()));
		show();
	}

	private void schedule(long delayMs) {
		mainHandler.removeCallbacks(check);
		if (delayMs != TorPublishState.NO_CHECK) {
			mainHandler.postDelayed(check, delayMs);
		}
	}

	private void show() {
		published.setValue(state.isPublished());
		clockSkewSeconds.setValue(state.getSkewSeconds());
	}

	private long currentSkew() {
		Plugin p = pluginManager.getPlugin(TorConstants.ID);
		return p instanceof TorClockSkewStatus
				? ((TorClockSkewStatus) p).getCurrentClockSkewSeconds() : 0;
	}

	private static long now() {
		return SystemClock.elapsedRealtime();
	}
}
