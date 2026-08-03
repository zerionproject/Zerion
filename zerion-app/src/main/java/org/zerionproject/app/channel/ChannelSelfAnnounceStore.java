package org.zerionproject.app.channel;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class ChannelSelfAnnounceStore {

	private static final String NS = "zerion-channels-self-announce";

	private final SettingsManager settingsManager;

	@Inject
	ChannelSelfAnnounceStore(SettingsManager settingsManager) {
		this.settingsManager = settingsManager;
	}

	boolean hasAnnounced(byte[] channelId) throws DbException {
		Settings s = settingsManager.getSettings(NS);
		String v = s.get(ChannelStore.hex(channelId));
		return "1".equals(v);
	}

	void markAnnounced(byte[] channelId) throws DbException {
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId), "1");
		settingsManager.mergeSettings(out, NS);
	}

	void remove(byte[] channelId) throws DbException {
		Settings cur = settingsManager.getSettings(NS);
		String key = ChannelStore.hex(channelId);
		if (!cur.containsKey(key)) return;
		Settings out = new Settings();
		out.put(key, "");
		settingsManager.mergeSettings(out, NS);
	}
}
