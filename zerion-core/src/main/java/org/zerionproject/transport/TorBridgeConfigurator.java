package org.zerionproject.transport;

import org.zerionproject.tor.CircumventionProvider;
import org.zerionproject.tor.CircumventionProvider.BridgeType;
import org.zerionproject.tor.LocationUtils;
import org.zerionproject.tor.TorWrapper;
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

	/** Whether the last bridge apply was refused by Tor. */
	private volatile boolean lastApplyFailed = false;

	/**
	 * Applies the bridge setting the user chose. Returns false when bridges
	 * are wanted but none could be configured, in which case the caller
	 * keeps the network disabled. After a refusal the bridges are cleared
	 * before the next attempt, because the wrapper remembers the last list
	 * it was handed and would report an identical list as already applied.
	 */
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
		if (bridges.isEmpty()) {
			lastApplyFailed = true;
			return false;
		}
		try {
			if (lastApplyFailed) {
				try {
					tor.disableBridges();
				} catch (IOException ignored) {
				}
			}
			tor.enableBridges(bridges);
			lastApplyFailed = false;
			return true;
		} catch (IOException e) {
			lastApplyFailed = true;
			return false;
		}
	}

	/**
	 * A bridge line as Tor accepts it: an optional pluggable transport name,
	 * an address with a port, and optional further tokens (a fingerprint,
	 * key=value arguments). Nothing else, and in particular no character
	 * that could end the line or the option early.
	 */
	public static boolean isPlausibleBridgeLine(String line) {
		String t = line.trim();
		if (t.isEmpty() || t.length() > 512) return false;
		for (int i = 0; i < t.length(); i++) {
			char c = t.charAt(i);
			if (c < 0x20 || c == 0x7F || c == '"' || c == '\\') return false;
		}
		String[] tokens = t.split(" +");
		int i = looksLikeAddress(tokens[0]) ? 0 : 1;
		if (i == 1 && !tokens[0].matches("[A-Za-z0-9_]+")) return false;
		if (i >= tokens.length) return false;
		if (!looksLikeAddress(tokens[i])) return false;
		for (int k = i + 1; k < tokens.length; k++) {
			if (!tokens[k].matches("[A-Za-z0-9_.:=/+-]+")) return false;
		}
		return true;
	}

	private static boolean looksLikeAddress(String s) {
		int colon = s.lastIndexOf(':');
		if (colon <= 0 || colon == s.length() - 1) return false;
		String host = s.substring(0, colon);
		String port = s.substring(colon + 1);
		if (!port.matches("[0-9]{1,5}")) return false;
		int p = Integer.parseInt(port);
		if (p < 1 || p > 65535) return false;
		if (host.startsWith("[") && host.endsWith("]")) {
			return host.substring(1, host.length() - 1).matches("[0-9A-Fa-f:.]+");
		}
		return host.matches("[A-Za-z0-9.-]+");
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
