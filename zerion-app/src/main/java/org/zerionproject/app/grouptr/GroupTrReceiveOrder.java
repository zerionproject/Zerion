package org.zerionproject.app.grouptr;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.app.grouptr.GroupTrConstants.MAX_GROUP_MEMBERS;
import static org.zerionproject.core.util.StringUtils.toHexString;

@ThreadSafe
@NotNullByDefault
final class GroupTrReceiveOrder {

	static final String NAMESPACE_PREFIX = "grouptr-order:";
	static final String KEY_COMMITS = "commits";
	static final String KEY_DEPARTED = "departed";

	private final SettingsManager settingsManager;

	GroupTrReceiveOrder(SettingsManager settingsManager) {
		this.settingsManager = settingsManager;
	}

	void dropHeldCommits() {
		try {
			for (String ns : settingsManager.getNamespaces(NAMESPACE_PREFIX)) {
				if (settingsManager.getSettings(ns).get(KEY_COMMITS) == null) {
					continue;
				}
				settingsManager.deleteSettings(ns,
						Collections.singletonList(KEY_COMMITS));
			}
		} catch (DbException | RuntimeException ex) {
		}
	}

	void noteDeparture(Transaction txn, byte[] groupId, byte[] pubKey,
			long epoch) throws DbException {
		String ns = namespace(groupId);
		String stored = settingsManager.getSettings(txn, ns).get(KEY_DEPARTED);
		Map<String, Long> departed = parseDepartures(stored);
		String hex = toHexString(pubKey);
		Long before = departed.remove(hex);
		departed.put(hex, before == null ? epoch : Math.max(before, epoch));
		while (departed.size() > MAX_GROUP_MEMBERS) {
			departed.remove(departed.keySet().iterator().next());
		}
		storeDepartures(txn, ns, stored, departed);
	}

	void clearDepartureBelow(Transaction txn, byte[] groupId, byte[] pubKey,
			long epoch) throws DbException {
		String ns = namespace(groupId);
		String stored = settingsManager.getSettings(txn, ns).get(KEY_DEPARTED);
		Map<String, Long> departed = parseDepartures(stored);
		String hex = toHexString(pubKey);
		Long leftAt = departed.get(hex);
		if (leftAt == null || leftAt >= epoch) return;
		departed.remove(hex);
		storeDepartures(txn, ns, stored, departed);
	}

	Map<String, Long> departures(byte[] groupId) throws DbException {
		return parseDepartures(settingsManager.getSettings(namespace(groupId))
				.get(KEY_DEPARTED));
	}

	private void storeDepartures(Transaction txn, String ns,
			@Nullable String stored, Map<String, Long> departed)
			throws DbException {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Long> d : departed.entrySet()) {
			if (sb.length() > 0) sb.append(',');
			sb.append(d.getKey()).append('.').append(d.getValue());
		}
		String joined = sb.toString();
		if (joined.equals(stored)) return;
		Settings update = new Settings();
		update.put(KEY_DEPARTED, joined);
		settingsManager.mergeSettings(txn, update, ns);
	}

	static String namespace(byte[] groupId) {
		return NAMESPACE_PREFIX + toHexString(groupId);
	}

	private static Map<String, Long> parseDepartures(@Nullable String stored) {
		Map<String, Long> out = new LinkedHashMap<>();
		if (stored == null || stored.isEmpty()) return out;
		for (String entry : stored.split(",")) {
			String[] parts = entry.split("\\.");
			if (parts.length != 2) continue;
			try {
				out.put(parts[0], Long.parseLong(parts[1]));
			} catch (NumberFormatException ex) {
			}
		}
		return out;
	}
}
