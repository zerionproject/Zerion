package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;

import java.util.HashMap;
import java.util.Map;

/** Settings kept in memory, with the merge semantics of the real store. */
class InMemorySettingsManager implements SettingsManager {

	private final Map<String, Settings> byNamespace = new HashMap<>();

	@Override
	public Settings getSettings(String namespace) {
		Settings s = new Settings();
		Settings stored = byNamespace.get(namespace);
		if (stored != null) s.putAll(stored);
		return s;
	}

	@Override
	public Settings getSettings(Transaction txn, String namespace) {
		return getSettings(namespace);
	}

	@Override
	public void mergeSettings(Settings s, String namespace) {
		Settings stored = byNamespace.computeIfAbsent(namespace,
				k -> new Settings());
		stored.putAll(s);
	}

	@Override
	public void mergeSettings(Transaction txn, Settings s, String namespace) {
		mergeSettings(s, namespace);
	}
}
