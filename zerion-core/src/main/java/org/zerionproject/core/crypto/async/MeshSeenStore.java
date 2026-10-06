package org.zerionproject.core.crypto.async;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class MeshSeenStore {

	public static final long RETENTION_MS = 8L * 24 * 60 * 60 * 1000;
	public static final int MAX_IDS = 4096;

	private static final String NS = "org.zerionproject.async/meshSeen";
	private static final String KEY = "ids";
	private static final int RECORDED_BYTES = 16;

	private final SettingsManager settingsManager;
	private final Clock clock;
	private final Object lock = new Object();

	public MeshSeenStore(SettingsManager settingsManager) {
		this(settingsManager, new SystemClock());
	}

	public MeshSeenStore(SettingsManager settingsManager, Clock clock) {
		this.settingsManager = settingsManager;
		this.clock = clock;
	}

	public boolean checkAndMark(byte[] messageId) throws DbException {
		String recorded = recorded(messageId);
		String legacy = StringUtils.toHexString(messageId);
		synchronized (lock) {
			long now = clock.currentTimeMillis();
			boolean[] converted = new boolean[1];
			LinkedHashMap<String, Long> ids = load(now, converted);
			boolean changed = expire(ids, now) || converted[0];
			if (ids.containsKey(recorded) || ids.containsKey(legacy)) {
				if (changed) store(ids);
				return true;
			}
			ids.put(recorded, now + RETENTION_MS);
			Iterator<String> it = ids.keySet().iterator();
			while (ids.size() > MAX_IDS && it.hasNext()) {
				it.next();
				it.remove();
			}
			store(ids);
			return false;
		}
	}

	public void unmark(byte[] messageId) throws DbException {
		String recorded = recorded(messageId);
		String legacy = StringUtils.toHexString(messageId);
		synchronized (lock) {
			LinkedHashMap<String, Long> ids =
					load(clock.currentTimeMillis(), new boolean[1]);
			boolean removed = ids.remove(recorded) != null;
			removed |= ids.remove(legacy) != null;
			if (removed) store(ids);
		}
	}

	private static boolean expire(LinkedHashMap<String, Long> ids, long now) {
		return ids.values().removeIf(expiry -> expiry <= now);
	}

	private LinkedHashMap<String, Long> load(long now, boolean[] converted)
			throws DbException {
		String joined = settingsManager.getSettings(NS).get(KEY);
		LinkedHashMap<String, Long> ids = new LinkedHashMap<>();
		if (joined == null || joined.isEmpty()) return ids;
		for (String part : joined.split(",")) {
			if (part.isEmpty()) continue;
			int sep = part.indexOf(':');
			if (sep < 0) {
				ids.put(part, now + RETENTION_MS);
				converted[0] = true;
				continue;
			}
			try {
				ids.put(part.substring(0, sep),
						Long.parseLong(part.substring(sep + 1)));
			} catch (NumberFormatException e) {
				ids.put(part.substring(0, sep), now + RETENTION_MS);
				converted[0] = true;
			}
		}
		return ids;
	}

	private void store(LinkedHashMap<String, Long> ids) throws DbException {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Long> e : ids.entrySet()) {
			if (sb.length() > 0) sb.append(',');
			sb.append(e.getKey()).append(':').append(e.getValue());
		}
		Settings s = new Settings();
		s.put(KEY, sb.toString());
		settingsManager.mergeSettings(s, NS);
	}

	private static String recorded(byte[] messageId) {
		try {
			byte[] h = MessageDigest.getInstance("SHA-256").digest(messageId);
			return StringUtils.toHexString(Arrays.copyOf(h, RECORDED_BYTES));
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}
}
