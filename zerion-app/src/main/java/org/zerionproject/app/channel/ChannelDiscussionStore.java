package org.zerionproject.app.channel;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class ChannelDiscussionStore {

	private static final String NS = "zerion-channels-discussions";
	private static final String VALUE_ON = "1";
	private static final String VALUE_OFF = "0";

	private final SettingsManager settingsManager;

	@Inject
	ChannelDiscussionStore(SettingsManager settingsManager) {
		this.settingsManager = settingsManager;
	}

	boolean isEnabled(byte[] channelId) throws DbException {
		Settings s = settingsManager.getSettings(NS);
		String v = s.get(ChannelStore.hex(channelId));
		if (v == null) return true;
		return VALUE_ON.equals(v);
	}

	void setEnabled(byte[] channelId, boolean enabled) throws DbException {
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId),
				enabled ? VALUE_ON : VALUE_OFF);
		settingsManager.mergeSettings(out, NS);
	}

	void remove(byte[] channelId) throws DbException {
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId), "");
		settingsManager.mergeSettings(out, NS);
	}
}
