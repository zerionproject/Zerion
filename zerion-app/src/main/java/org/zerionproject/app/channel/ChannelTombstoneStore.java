package org.zerionproject.app.channel;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class ChannelTombstoneStore {

	private static final String NS =
			ChannelConstants.SETTINGS_NAMESPACE_TOMBSTONES;

	private final SettingsManager settingsManager;

	@Inject
	ChannelTombstoneStore(SettingsManager settingsManager) {
		this.settingsManager = settingsManager;
	}

	@Nullable
	byte[] get(byte[] channelId) throws DbException {
		Settings s = settingsManager.getSettings(NS);
		String encoded = s.get(ChannelStore.hex(channelId));
		if (encoded == null || encoded.isEmpty()) return null;
		try {
			return java.util.Base64.getDecoder().decode(encoded);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	void put(byte[] channelId, byte[] tombstoneBytes) throws DbException {
		settingsManager.mergeSettings(encode(channelId, tombstoneBytes), NS);
	}

	static String namespace() {
		return NS;
	}

	static Settings encode(byte[] channelId, byte[] tombstoneBytes) {
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId),
				java.util.Base64.getEncoder()
						.withoutPadding()
						.encodeToString(tombstoneBytes));
		return out;
	}

	void remove(byte[] channelId) throws DbException {
		settingsManager.deleteSettings(NS, java.util.Collections
				.singletonList(ChannelStore.hex(channelId)));
	}
}
