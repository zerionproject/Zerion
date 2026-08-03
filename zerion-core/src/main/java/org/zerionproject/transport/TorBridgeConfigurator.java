package org.zerionproject.transport;

import org.briarproject.onionwrapper.CircumventionProvider;
import org.briarproject.onionwrapper.CircumventionProvider.BridgeType;
import org.briarproject.onionwrapper.LocationUtils;
import org.briarproject.onionwrapper.TorWrapper;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.settings.event.SettingsUpdatedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;
import javax.inject.Singleton;

import static org.zerionproject.core.api.plugin.TorConstants.DEFAULT_PREF_TOR_NETWORK;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_CUSTOM_BRIDGES;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK_AUTOMATIC;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK_WITH_BRIDGES;

@Singleton
@NotNullByDefault
public class TorBridgeConfigurator implements EventListener {

	private final SettingsManager settingsManager;
	private final CircumventionProvider circumventionProvider;
	private final LocationUtils locationUtils;
	private final TorWrapper tor;
	private final Executor ioExecutor;

	@Inject
	public TorBridgeConfigurator(SettingsManager settingsManager,
			CircumventionProvider circumventionProvider,
			LocationUtils locationUtils, TorWrapper tor, EventBus eventBus,
			@IoExecutor Executor ioExecutor) {
		this.settingsManager = settingsManager;
		this.circumventionProvider = circumventionProvider;
		this.locationUtils = locationUtils;
		this.tor = tor;
		this.ioExecutor = ioExecutor;
		eventBus.addListener(this);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof SettingsUpdatedEvent) {
			SettingsUpdatedEvent s = (SettingsUpdatedEvent) e;
			if (TorConstants.ID.getString().equals(s.getNamespace())) {
				ioExecutor.execute(this::applyOrDisableNetwork);
			}
		}
	}

	private void applyOrDisableNetwork() {
		if (apply()) return;
		try {
			tor.enableNetwork(false);
		} catch (IOException e) {
		}
	}

	public boolean apply() {
		Settings s;
		try {
			s = settingsManager.getSettings(TorConstants.ID.getString());
		} catch (DbException e) {
			return false;
		}
		int network = s.getInt(PREF_TOR_NETWORK, DEFAULT_PREF_TOR_NETWORK);
		String country = locationUtils.getCurrentCountry();
		boolean useBridges = network == PREF_TOR_NETWORK_WITH_BRIDGES
				|| (network == PREF_TOR_NETWORK_AUTOMATIC
						&& circumventionProvider.shouldUseBridges(country));
		if (!useBridges) {
			try {
				tor.disableBridges();
			} catch (IOException e) {
			}
			return true;
		}
		List<String> bridges;
		String custom = s.get(PREF_TOR_CUSTOM_BRIDGES);
		if (custom != null && !custom.trim().isEmpty()) {
			bridges = parseCustomBridges(custom);
		} else {
			bridges = new ArrayList<>();
			for (BridgeType type :
					circumventionProvider.getSuitableBridgeTypes(country)) {
				bridges.addAll(circumventionProvider.getBridges(type, country));
			}
		}
		if (bridges.isEmpty()) return false;
		try {
			tor.enableBridges(bridges);
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	static List<String> parseCustomBridges(String value) {
		List<String> bridges = new ArrayList<>();
		for (String line : value.split("\\r?\\n")) {
			String trimmed = line.trim();
			if (trimmed.isEmpty()) continue;
			String lower = trimmed.toLowerCase(java.util.Locale.US);
			if (lower.startsWith("bridge ")) {
				bridges.add(trimmed);
			} else {
				bridges.add("Bridge " + trimmed);
			}
		}
		return bridges;
	}
}
