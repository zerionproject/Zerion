package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class ChannelDiscussionStore {

	private static final String NS = "zerion-channels-discussions";
	private static final String NS_EXCEPTIONS =
			"zerion-channels-discussion-exceptions";
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

	boolean isEnabledFor(byte[] channelId, long postSeqNum)
			throws DbException {
		return isEnabled(channelId) != exceptions(channelId)
				.contains(postSeqNum);
	}

	Set<Long> exceptions(byte[] channelId) throws DbException {
		String packed = settingsManager.getSettings(NS_EXCEPTIONS)
				.get(ChannelStore.hex(channelId));
		TreeSet<Long> out = new TreeSet<>();
		if (packed == null || packed.isEmpty()) return out;
		for (String tok : packed.split(",")) {
			if (tok.isEmpty()) continue;
			try {
				long seq = Long.parseLong(tok);
				if (seq >= 0L) out.add(seq);
			} catch (NumberFormatException ignored) {
			}
		}
		return out;
	}

	void setEnabled(byte[] channelId, boolean enabled) throws DbException {
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId),
				enabled ? VALUE_ON : VALUE_OFF);
		settingsManager.mergeSettings(out, NS);
	}

	void setState(byte[] channelId, boolean enabled,
			Collection<Long> exceptions) throws DbException {
		String key = ChannelStore.hex(channelId);
		Settings def = new Settings();
		def.put(key, enabled ? VALUE_ON : VALUE_OFF);
		Settings x = new Settings();
		x.put(key, pack(exceptions));
		Map<String, Settings> batch = new LinkedHashMap<>();
		batch.put(NS, def);
		batch.put(NS_EXCEPTIONS, x);
		settingsManager.mergeSettings(batch);
	}

	boolean setPost(byte[] channelId, long postSeqNum, boolean enabled,
			Set<Long> held) throws DbException {
		TreeSet<Long> next = new TreeSet<>(exceptions(channelId));
		next.retainAll(held);
		if (enabled == isEnabled(channelId)) next.remove(postSeqNum);
		else next.add(postSeqNum);
		if (next.size() > ChannelConstants.MAX_COMMENT_EXCEPTIONS_PER_CHANNEL) {
			return false;
		}
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId), pack(next));
		settingsManager.mergeSettings(out, NS_EXCEPTIONS);
		return true;
	}

	boolean takeFromPublisher(byte[] channelId, @Nullable Boolean enabled,
			Collection<Long> exceptions) throws DbException {
		String key = ChannelStore.hex(channelId);
		String held = settingsManager.getSettings(NS).get(key);
		String heldExceptions =
				settingsManager.getSettings(NS_EXCEPTIONS).get(key);
		if (enabled == null) {
			if (held == null && heldExceptions == null) return false;
			remove(channelId);
			return true;
		}
		String wanted = enabled ? VALUE_ON : VALUE_OFF;
		String packed = pack(exceptions);
		if (wanted.equals(held) && packed.equals(heldExceptions == null
				? "" : heldExceptions)) {
			return false;
		}
		setState(channelId, enabled, exceptions);
		return true;
	}

	void remove(byte[] channelId) throws DbException {
		settingsManager.deleteSettings(NS, Collections
				.singletonList(ChannelStore.hex(channelId)));
		settingsManager.deleteSettings(NS_EXCEPTIONS, Collections
				.singletonList(ChannelStore.hex(channelId)));
	}

	private static String pack(Collection<Long> seqs) {
		StringBuilder sb = new StringBuilder();
		for (Long seq : new TreeSet<>(seqs)) {
			if (sb.length() > 0) sb.append(',');
			sb.append(seq);
		}
		return sb.toString();
	}
}
