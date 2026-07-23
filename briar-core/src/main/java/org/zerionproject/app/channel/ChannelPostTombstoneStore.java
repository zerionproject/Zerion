package org.zerionproject.app.channel;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class ChannelPostTombstoneStore {

	private static final String NS = "zerion-channels-post-tombstones";

	private final SettingsManager settingsManager;

	@Inject
	ChannelPostTombstoneStore(SettingsManager settingsManager) {
		this.settingsManager = settingsManager;
	}

	Set<Long> get(byte[] channelId) throws DbException {
		Settings s = settingsManager.getSettings(NS);
		String packed = s.get(ChannelStore.hex(channelId));
		if (packed == null || packed.isEmpty()) {
			return Collections.emptySet();
		}
		Set<Long> out = new HashSet<>();
		for (String tok : packed.split(",")) {
			if (tok.isEmpty()) continue;
			try {
				out.add(Long.parseLong(tok));
			} catch (NumberFormatException ignored) {
			}
		}
		return out;
	}

	void add(byte[] channelId, long seqNum) throws DbException {
		Set<Long> current = new HashSet<>(get(channelId));
		if (!current.add(seqNum)) return;
		write(channelId, current);
	}

	void removeAll(byte[] channelId) throws DbException {
		Settings cur = settingsManager.getSettings(NS);
		String key = ChannelStore.hex(channelId);
		if (!cur.containsKey(key)) return;
		Settings out = new Settings();
		out.put(key, "");
		settingsManager.mergeSettings(out, NS);
	}

	private void write(byte[] channelId, Set<Long> seqs) throws DbException {
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (Long seq : seqs) {
			if (!first) sb.append(',');
			sb.append(seq);
			first = false;
		}
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId), sb.toString());
		settingsManager.mergeSettings(out, NS);
	}
}
