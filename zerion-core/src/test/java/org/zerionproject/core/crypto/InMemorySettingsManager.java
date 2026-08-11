package org.zerionproject.core.crypto;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;

import java.util.HashMap;
import java.util.Map;

/** A non-persistent SettingsManager for testing the async prekey store. */
class InMemorySettingsManager implements SettingsManager {

	private final Map<String, Settings> store = new HashMap<>();

	@Override
	public synchronized Settings getSettings(String namespace) {
		Settings copy = new Settings();
		Settings s = store.get(namespace);
		if (s != null) copy.putAll(s);
		return copy;
	}

	@Override
	public Settings getSettings(Transaction txn, String namespace) {
		return getSettings(namespace);
	}

	@Override
	public synchronized void mergeSettings(Settings s, String namespace) {
		Settings existing = store.get(namespace);
		if (existing == null) {
			existing = new Settings();
			store.put(namespace, existing);
		}
		existing.putAll(s);
	}

	@Override
	public void mergeSettings(Transaction txn, Settings s, String namespace)
			throws DbException {
		mergeSettings(s, namespace);
	}
}
