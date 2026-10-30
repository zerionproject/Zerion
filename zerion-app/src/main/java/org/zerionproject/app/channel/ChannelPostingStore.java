package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collections;

import javax.annotation.Nullable;

@NotNullByDefault
final class ChannelPostingStore {

	static final String NS = "zerion-channels-posting";
	static final String NS_REFUSED = "zerion-channels-posts-not-accepted";
	static final String NS_TOO_MANY = "zerion-channels-posts-too-many";
	static final String NS_QUOTA_PREFIX = "zerion-channels-member-quota:";

	private final SettingsManager settings;

	ChannelPostingStore(SettingsManager settings) {
		this.settings = settings;
	}

	int mode(byte[] channelId) throws DbException {
		String v = settings.getSettings(NS).get(ChannelStore.hex(channelId));
		if (v == null) return ChannelConstants.POSTING_EDITORS;
		try {
			int mode = Integer.parseInt(v);
			return ChannelMemberPosts.validMode(mode) ? mode
					: ChannelConstants.POSTING_EDITORS;
		} catch (NumberFormatException e) {
			return ChannelConstants.POSTING_EDITORS;
		}
	}

	void setMode(byte[] channelId, int mode) throws DbException {
		if (!ChannelMemberPosts.validMode(mode)) throw new DbException();
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId), String.valueOf(mode));
		settings.mergeSettings(out, NS);
	}

	boolean takeFromPublisher(byte[] channelId, @Nullable Integer mode)
			throws DbException {
		String key = ChannelStore.hex(channelId);
		String held = settings.getSettings(NS).get(key);
		if (mode == null) {
			if (held == null) return false;
			settings.deleteSettings(NS, Collections.singletonList(key));
			return true;
		}
		if (String.valueOf(mode).equals(held)) return false;
		setMode(channelId, mode);
		return true;
	}

	void noteRefused(byte[] channelId) throws DbException {
		note(NS_REFUSED, channelId);
	}

	int takeRefused(byte[] channelId) throws DbException {
		return take(NS_REFUSED, channelId);
	}

	void noteTooMany(byte[] channelId) throws DbException {
		note(NS_TOO_MANY, channelId);
	}

	int takeTooMany(byte[] channelId) throws DbException {
		return take(NS_TOO_MANY, channelId);
	}

	private void note(String ns, byte[] channelId) throws DbException {
		String key = ChannelStore.hex(channelId);
		Settings out = new Settings();
		out.put(key, String.valueOf(count(ns, channelId) + 1));
		settings.mergeSettings(out, ns);
	}

	private int take(String ns, byte[] channelId) throws DbException {
		int n = count(ns, channelId);
		if (n > 0) {
			settings.deleteSettings(ns, Collections.singletonList(
					ChannelStore.hex(channelId)));
		}
		return n;
	}

	private int count(String ns, byte[] channelId) throws DbException {
		String v = settings.getSettings(ns).get(
				ChannelStore.hex(channelId));
		if (v == null) return 0;
		try {
			return Math.max(0, Math.min(Integer.parseInt(v),
					ChannelOutboxStore.MAX_ENTRIES));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	static String quotaNamespace(byte[] channelId) {
		return NS_QUOTA_PREFIX + ChannelStore.hex(channelId);
	}

	void remove(byte[] channelId) throws DbException {
		String key = ChannelStore.hex(channelId);
		settings.deleteSettings(NS, Collections.singletonList(key));
		settings.deleteSettings(NS_REFUSED, Collections.singletonList(key));
		settings.deleteSettings(NS_TOO_MANY, Collections.singletonList(key));
		settings.deleteNamespaces(Collections.singletonList(
				quotaNamespace(channelId)));
	}
}
