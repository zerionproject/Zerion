package org.zerionproject.core.api.settings;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.Map;

import javax.annotation.Nullable;

@NotNullByDefault
public interface SettingsManager {

	Settings getSettings(String namespace) throws DbException;

	Settings getSettings(Transaction txn, String namespace) throws DbException;

	@Nullable
	default String getSetting(String namespace, String key)
			throws DbException {
		return getSettings(namespace).get(key);
	}

	void mergeSettings(Settings s, String namespace) throws DbException;

	void mergeSettings(Transaction txn, Settings s, String namespace)
			throws DbException;

	default void mergeSettings(Map<String, Settings> byNamespace)
			throws DbException {
		for (Map.Entry<String, Settings> e : byNamespace.entrySet()) {
			mergeSettings(e.getValue(), e.getKey());
		}
	}

	default void deleteSettings(String namespace, Collection<String> keys)
			throws DbException {
		if (keys.isEmpty()) return;
		Settings blank = new Settings();
		for (String key : keys) blank.put(key, "");
		mergeSettings(blank, namespace);
	}

	default void deleteNamespaces(Collection<String> namespaces)
			throws DbException {
		for (String namespace : namespaces) {
			Settings stored = getSettings(namespace);
			if (stored.isEmpty()) continue;
			deleteSettings(namespace, stored.keySet());
		}
	}
}
