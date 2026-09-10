package org.zerionproject.sync;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.network.NetworkManager;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.settings.event.SettingsUpdatedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.core.api.sync.ZppPacingConstants.DEFAULT_REDUCE_MOBILE_DATA;
import static org.zerionproject.core.api.sync.ZppPacingConstants.PREF_REDUCE_MOBILE_DATA;
import static org.zerionproject.core.api.sync.ZppPacingConstants.SETTINGS_NAMESPACE;

/**
 * The production pacing policy: an active regime at the classic 750 ms slot,
 * and an idle regime of 4 s, stretched to 8 s on metered (non-wifi) networks
 * unless the user turns the mobile-data reduction off. Each regime is a
 * constant, jittered rate; only the choice between them depends on whether
 * application records flowed recently, so an observer of an established
 * connection learns at most the coarse onset and end of activity, never
 * which frames carried data.
 */
@ThreadSafe
@NotNullByDefault
public class ZppPacingPolicy implements ZppPacing, EventListener {

	static final long ACTIVE_INTERVAL_MS = 750;
	static final long IDLE_INTERVAL_MS = 4_000;
	static final long IDLE_INTERVAL_METERED_MS = 8_000;
	static final long IDLE_AFTER_MS = 2 * 60_000;
	private static final long NETWORK_CACHE_MS = 30_000;

	private final NetworkManager networkManager;
	private final SettingsManager settingsManager;
	private final Executor dbExecutor;
	private final AtomicBoolean settingLoaded = new AtomicBoolean(false);

	private volatile boolean reduceMobileData = DEFAULT_REDUCE_MOBILE_DATA;
	private volatile boolean metered = false;
	private volatile long meteredCheckedAt = 0;

	public ZppPacingPolicy(NetworkManager networkManager,
			SettingsManager settingsManager, Executor dbExecutor) {
		this.networkManager = networkManager;
		this.settingsManager = settingsManager;
		this.dbExecutor = dbExecutor;
	}

	@Override
	public long activeIntervalMs() {
		return ACTIVE_INTERVAL_MS;
	}

	@Override
	public long idleIntervalMs() {
		loadSettingOnce();
		if (!reduceMobileData) return IDLE_INTERVAL_MS;
		return isMetered() ? IDLE_INTERVAL_METERED_MS : IDLE_INTERVAL_MS;
	}

	@Override
	public long idleAfterMs() {
		return IDLE_AFTER_MS;
	}

	private boolean isMetered() {
		long now = System.currentTimeMillis();
		if (now - meteredCheckedAt > NETWORK_CACHE_MS) {
			meteredCheckedAt = now;
			try {
				org.zerionproject.core.api.network.NetworkStatus status =
						networkManager.getNetworkStatus();
				metered = status.isConnected() && !status.isWifi();
			} catch (RuntimeException e) {
				metered = false;
			}
		}
		return metered;
	}

	private void loadSettingOnce() {
		if (!settingLoaded.compareAndSet(false, true)) return;
		dbExecutor.execute(() -> {
			try {
				Settings s = settingsManager.getSettings(SETTINGS_NAMESPACE);
				reduceMobileData = s.getBoolean(PREF_REDUCE_MOBILE_DATA,
						DEFAULT_REDUCE_MOBILE_DATA);
			} catch (DbException e) {
				settingLoaded.set(false);
			}
		});
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof SettingsUpdatedEvent) {
			SettingsUpdatedEvent s = (SettingsUpdatedEvent) e;
			if (SETTINGS_NAMESPACE.equals(s.getNamespace())) {
				reduceMobileData = s.getSettings().getBoolean(
						PREF_REDUCE_MOBILE_DATA, DEFAULT_REDUCE_MOBILE_DATA);
				settingLoaded.set(true);
			}
		}
	}
}
