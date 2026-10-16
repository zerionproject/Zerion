package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelSyncStatus;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

import javax.annotation.Nullable;

@NotNullByDefault
class ChannelSyncStatusStore {

	static final String NS = "zerion-channels-sync-status";
	static final String NS_DELETED = "zerion-channels-deleted-notices";
	static final long DORMANT_AFTER_MS = 90L * 24L * 60L * 60L * 1000L;
	static final int MAX_DELETED_NOTICES = 32;
	static final long DELETED_NOTICE_DAYS = 30L;
	private static final String DELETED_KEY = "names";
	private static final String NAME_PREFIX = "n";
	private static final char DAY_SEPARATOR = '.';
	private static final long MINUTE_MS = 60_000L;
	private static final long DAY_MS = 24L * 60L * MINUTE_MS;

	private final SettingsManager settingsManager;
	private final Object deletedLock = new Object();

	ChannelSyncStatusStore(SettingsManager settingsManager) {
		this.settingsManager = settingsManager;
	}

	static final class Record {

		final long lastOkMs;
		final long sinceMs;
		final boolean failed;

		Record(long lastOkMs, long sinceMs, boolean failed) {
			this.lastOkMs = lastOkMs;
			this.sinceMs = sinceMs;
			this.failed = failed;
		}

		long dormantAt() {
			long reference = lastOkMs >= 0L ? lastOkMs : sinceMs;
			return reference >= 0L ? reference + DORMANT_AFTER_MS
					: Long.MAX_VALUE;
		}

		boolean dormant(long now) {
			return now >= dormantAt();
		}

		ChannelSyncStatus status(long now) {
			return new ChannelSyncStatus(lastOkMs >= 0L ? lastOkMs
					: ChannelSyncStatus.NEVER, failed, dormant(now));
		}
	}

	@Nullable
	Record get(byte[] channelId) throws DbException {
		String v = settingsManager.getSettings(NS)
				.get(ChannelStore.hex(channelId));
		if (v == null || v.isEmpty()) return null;
		String[] f = v.split(",", -1);
		if (f.length != 3) return null;
		try {
			return new Record(Long.parseLong(f[0]), Long.parseLong(f[1]),
					"1".equals(f[2]));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	ChannelSyncStatus status(byte[] channelId, long now) throws DbException {
		Record r = get(channelId);
		return r == null ? ChannelSyncStatus.UNKNOWN : r.status(now);
	}

	long dormantAt(byte[] channelId) throws DbException {
		Record r = get(channelId);
		return r == null ? Long.MAX_VALUE : r.dormantAt();
	}

	Record noteSuccess(byte[] channelId, long now) throws DbException {
		Record r = get(channelId);
		if (r != null && !r.failed && r.lastOkMs >= 0L
				&& r.lastOkMs / MINUTE_MS == now / MINUTE_MS) {
			return r;
		}
		return put(channelId, new Record(now, -1L, false));
	}

	Record noteFailure(byte[] channelId, long now) throws DbException {
		Record r = get(channelId);
		if (r == null) return put(channelId, new Record(-1L, now, true));
		if (r.failed) return r;
		return put(channelId, new Record(r.lastOkMs, r.sinceMs, true));
	}

	void remove(byte[] channelId) throws DbException {
		settingsManager.deleteSettings(NS, Collections.singletonList(
				ChannelStore.hex(channelId)));
	}

	private Record put(byte[] channelId, Record r) throws DbException {
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId), r.lastOkMs + "," + r.sinceMs
				+ "," + (r.failed ? "1" : "0"));
		settingsManager.mergeSettings(out, NS);
		return r;
	}

	private static final class Notice {

		final String name;
		final long day;

		Notice(String name, long day) {
			this.name = name;
			this.day = day;
		}
	}

	void addDeletedNotice(String name, long now) throws DbException {
		synchronized (deletedLock) {
			List<Notice> notices = readDeleted(now);
			removeNamed(notices, name);
			notices.add(new Notice(name, now / DAY_MS));
			while (notices.size() > MAX_DELETED_NOTICES) notices.remove(0);
			writeDeleted(notices);
		}
	}

	List<String> deletedNotices(long now) throws DbException {
		synchronized (deletedLock) {
			List<Notice> notices = keptNotices(now);
			List<String> names = new ArrayList<>(notices.size());
			for (Notice n : notices) names.add(n.name);
			return names;
		}
	}

	void purgeDeletedNotices(long now) throws DbException {
		synchronized (deletedLock) {
			keptNotices(now);
		}
	}

	void dismissDeletedNotice(String name, long now) throws DbException {
		synchronized (deletedLock) {
			List<Notice> notices = readDeleted(now);
			if (!removeNamed(notices, name)) return;
			writeDeleted(notices);
		}
	}

	private List<Notice> keptNotices(long now) throws DbException {
		String stored = settingsManager.getSetting(NS_DELETED, DELETED_KEY);
		List<Notice> notices = readDeleted(now);
		long today = now / DAY_MS;
		List<Notice> kept = new ArrayList<>(notices.size());
		for (Notice n : notices) {
			if (today - n.day < DELETED_NOTICE_DAYS) kept.add(n);
		}
		if (stored != null && !stored.isEmpty()
				&& !encode(kept).equals(stored)) {
			writeDeleted(kept);
		}
		return kept;
	}

	private static boolean removeNamed(List<Notice> notices, String name) {
		boolean removed = false;
		for (java.util.Iterator<Notice> it = notices.iterator();
				it.hasNext(); ) {
			if (it.next().name.equals(name)) {
				it.remove();
				removed = true;
			}
		}
		return removed;
	}

	private void writeDeleted(List<Notice> notices) throws DbException {
		if (notices.isEmpty()) {
			settingsManager.deleteSettings(NS_DELETED,
					Collections.singletonList(DELETED_KEY));
			return;
		}
		Settings out = new Settings();
		out.put(DELETED_KEY, encode(notices));
		settingsManager.mergeSettings(out, NS_DELETED);
	}

	private static String encode(List<Notice> notices) {
		StringBuilder sb = new StringBuilder();
		Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
		for (Notice n : notices) {
			if (sb.length() > 0) sb.append(',');
			sb.append(NAME_PREFIX).append(enc.encodeToString(
					n.name.getBytes(StandardCharsets.UTF_8)))
					.append(DAY_SEPARATOR).append(n.day);
		}
		return sb.toString();
	}

	private List<Notice> readDeleted(long now) throws DbException {
		List<Notice> out = new ArrayList<>();
		String v = settingsManager.getSetting(NS_DELETED, DELETED_KEY);
		if (v == null || v.isEmpty()) return out;
		Base64.Decoder dec = Base64.getUrlDecoder();
		for (String e : v.split(",", -1)) {
			if (!e.startsWith(NAME_PREFIX)) continue;
			String body = e.substring(NAME_PREFIX.length());
			long day = now / DAY_MS;
			int dot = body.indexOf(DAY_SEPARATOR);
			try {
				if (dot >= 0) {
					day = Long.parseLong(body.substring(dot + 1));
					body = body.substring(0, dot);
				}
				out.add(new Notice(new String(dec.decode(body),
						StandardCharsets.UTF_8), day));
			} catch (IllegalArgumentException ignored) {
			}
		}
		return out;
	}
}
