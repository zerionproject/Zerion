package org.zerionproject.core.plugin.tor.auth;

import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

	@Override
	public void deleteSettings(String namespace, Collection<String> keys) {
		Settings stored = byNamespace.get(namespace);
		if (stored != null) stored.keySet().removeAll(keys);
	}

	@Override
	public void deleteSettings(Transaction txn, String namespace,
			Collection<String> keys) {
		deleteSettings(namespace, keys);
	}

	Set<String> keys(String namespace) {
		Settings stored = byNamespace.get(namespace);
		return stored == null ? new HashSet<>() : new HashSet<>(stored.keySet());
	}

	void forget(String namespace) {
		byNamespace.remove(namespace);
	}
}
