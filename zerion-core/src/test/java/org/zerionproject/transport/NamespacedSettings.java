package org.zerionproject.transport;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class NamespacedSettings implements SettingsManager {

	final Map<String, Settings> byNamespace = new HashMap<>();
	final List<String> written = new ArrayList<>();
	boolean failing = false;

	Settings namespace(String namespace) {
		Settings s = byNamespace.get(namespace);
		if (s == null) {
			s = new Settings();
			byNamespace.put(namespace, s);
		}
		return s;
	}

	@Override
	public Settings getSettings(String namespace) throws DbException {
		if (failing) throw new DbException();
		Settings copy = new Settings();
		Settings stored = byNamespace.get(namespace);
		if (stored != null) copy.putAll(stored);
		return copy;
	}

	@Override
	public Settings getSettings(Transaction txn, String namespace)
			throws DbException {
		return getSettings(namespace);
	}

	@Override
	public void mergeSettings(Settings s, String namespace)
			throws DbException {
		if (failing) throw new DbException();
		written.add(namespace);
		namespace(namespace).putAll(s);
	}

	@Override
	public void mergeSettings(Transaction txn, Settings s, String namespace)
			throws DbException {
		mergeSettings(s, namespace);
	}

	@Override
	public void deleteSettings(String namespace, Collection<String> keys)
			throws DbException {
		if (failing) throw new DbException();
		written.add(namespace);
		namespace(namespace).keySet().removeAll(keys);
	}

	static final class TestClock implements Clock {
		long now;

		TestClock(long now) {
			this.now = now;
		}

		@Override
		public long currentTimeMillis() {
			return now;
		}

		@Override
		public void sleep(long milliseconds) {
		}
	}
}
