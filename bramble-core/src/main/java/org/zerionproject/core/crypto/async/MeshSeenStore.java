package org.zerionproject.core.crypto.async;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class MeshSeenStore {

	private static final String NS = "org.zerionproject.async/meshSeen";
	private static final String KEY = "ids";
	private static final int MAX_IDS = 1024;

	private final SettingsManager settingsManager;
	private final Object lock = new Object();

	public MeshSeenStore(SettingsManager settingsManager) {
		this.settingsManager = settingsManager;
	}

	public boolean checkAndMark(byte[] messageId) throws DbException {
		String id = StringUtils.toHexString(messageId);
		synchronized (lock) {
			Set<String> ids = load();
			if (ids.contains(id)) return true;
			ids.add(id);
			while (ids.size() > MAX_IDS) {
				ids.remove(ids.iterator().next());
			}
			store(ids);
			return false;
		}
	}

	public void unmark(byte[] messageId) throws DbException {
		String id = StringUtils.toHexString(messageId);
		synchronized (lock) {
			Set<String> ids = load();
			if (ids.remove(id)) store(ids);
		}
	}

	private Set<String> load() throws DbException {
		String joined = settingsManager.getSettings(NS).get(KEY);
		Set<String> ids = new LinkedHashSet<>();
		if (joined == null || joined.isEmpty()) return ids;
		for (String part : joined.split(",")) {
			if (!part.isEmpty()) ids.add(part);
		}
		return ids;
	}

	private void store(Set<String> ids) throws DbException {
		List<String> list = new ArrayList<>(ids);
		Settings s = new Settings();
		s.put(KEY, String.join(",", list));
		settingsManager.mergeSettings(s, NS);
	}
}
