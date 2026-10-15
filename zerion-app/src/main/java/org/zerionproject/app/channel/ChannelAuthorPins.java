package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class ChannelAuthorPins {

	private static final String NS_PREFIX = "zerion-channels-author-pins:";
	private static final String KEY_NEXT = "n";
	private static final String KEY_SEEDED = "s";
	private static final String KEY_BOUND_REPLIES = "v";
	private static final int AUTHOR_KEY_CHARS = 64;
	private static final int PIN_BYTES = 32;
	private static final int TRIM_EVERY = 256;
	private static final int BULK_READ_AT = 16;

	interface Held {

		Set<String> authors() throws DbException;
	}

	private final SettingsManager settings;
	private final int max;
	private final Set<String> seeded = new HashSet<>();
	private final Set<String> boundReplies = new HashSet<>();

	ChannelAuthorPins(SettingsManager settings) {
		this(settings, ChannelConstants.MAX_PINNED_AUTHORS_PER_CHANNEL);
	}

	ChannelAuthorPins(SettingsManager settings, int max) {
		this.settings = settings;
		this.max = max;
	}

	static String namespace(byte[] channelId) {
		return NS_PREFIX + ChannelStore.hex(channelId);
	}

	@Nullable
	synchronized byte[] get(byte[] channelId, byte[] ed25519PubKey)
			throws DbException {
		return pinOf(settings.getSetting(namespace(channelId),
				ChannelStore.hex(ed25519PubKey)));
	}

	synchronized Map<String, byte[]> getAll(byte[] channelId)
			throws DbException {
		Map<String, byte[]> out = new HashMap<>();
		for (Map.Entry<String, String> e
				: settings.getSettings(namespace(channelId)).entrySet()) {
			byte[] pin = isAuthor(e.getKey()) ? pinOf(e.getValue()) : null;
			if (pin != null) out.put(e.getKey(), pin);
		}
		return out;
	}

	synchronized void put(byte[] channelId, Map<String, byte[]> pins,
			Held held) throws DbException {
		if (pins.isEmpty()) return;
		String ns = namespace(channelId);
		Settings existing = pins.size() >= BULK_READ_AT
				? settings.getSettings(ns) : null;
		long next = counterOf(existing != null ? existing.get(KEY_NEXT)
				: settings.getSetting(ns, KEY_NEXT));
		Settings out = new Settings();
		boolean trim = false;
		for (Map.Entry<String, byte[]> e : pins.entrySet()) {
			if (!isAuthor(e.getKey()) || e.getValue().length != PIN_BYTES) {
				continue;
			}
			String stored = existing != null ? existing.get(e.getKey())
					: settings.getSetting(ns, e.getKey());
			if (pinOf(stored) != null || out.containsKey(e.getKey())) continue;
			out.put(e.getKey(), next + ":" + ChannelStore.hex(e.getValue()));
			next++;
			if (next % TRIM_EVERY == 0L) trim = true;
		}
		if (out.isEmpty()) return;
		out.put(KEY_NEXT, Long.toString(next));
		settings.mergeSettings(out, ns);
		if (trim) trim(ns, held);
	}

	synchronized boolean seeded(byte[] channelId) throws DbException {
		String key = ChannelStore.hex(channelId);
		if (seeded.contains(key)) return true;
		if ("1".equals(settings.getSetting(namespace(channelId),
				KEY_SEEDED))) {
			seeded.add(key);
			return true;
		}
		return false;
	}

	synchronized void seed(byte[] channelId, Map<String, byte[]> pins,
			Held held) throws DbException {
		if (pins.isEmpty()) {
			seeded.add(ChannelStore.hex(channelId));
			return;
		}
		String ns = namespace(channelId);
		Settings existing = settings.getSettings(ns);
		long next = counterOf(existing.get(KEY_NEXT));
		Settings out = new Settings();
		for (Map.Entry<String, byte[]> e : pins.entrySet()) {
			if (!isAuthor(e.getKey()) || e.getValue().length != PIN_BYTES
					|| pinOf(existing.get(e.getKey())) != null) {
				continue;
			}
			out.put(e.getKey(), next + ":" + ChannelStore.hex(e.getValue()));
			next++;
		}
		out.put(KEY_NEXT, Long.toString(next));
		out.put(KEY_SEEDED, "1");
		settings.mergeSettings(out, ns);
		seeded.add(ChannelStore.hex(channelId));
		trim(ns, held);
	}

	synchronized boolean boundReplies(byte[] channelId) throws DbException {
		String key = ChannelStore.hex(channelId);
		if (boundReplies.contains(key)) return true;
		if (Integer.toString(ChannelConstants.PROTOCOL_VERSION_AUTHOR_BINDING)
				.equals(settings.getSetting(namespace(channelId),
						KEY_BOUND_REPLIES))) {
			boundReplies.add(key);
			return true;
		}
		return false;
	}

	synchronized void markBoundReplies(byte[] channelId) throws DbException {
		if (boundReplies(channelId)) return;
		Settings out = new Settings();
		out.put(KEY_BOUND_REPLIES, Integer.toString(
				ChannelConstants.PROTOCOL_VERSION_AUTHOR_BINDING));
		settings.mergeSettings(out, namespace(channelId));
		boundReplies.add(ChannelStore.hex(channelId));
	}

	synchronized void removeAll(byte[] channelId) throws DbException {
		settings.deleteNamespaces(
				Collections.singletonList(namespace(channelId)));
		String key = ChannelStore.hex(channelId);
		seeded.remove(key);
		boundReplies.remove(key);
	}

	private void trim(String ns, Held held) throws DbException {
		Settings all = settings.getSettings(ns);
		List<String> keys = new ArrayList<>();
		List<Long> counters = new ArrayList<>();
		for (Map.Entry<String, String> e : all.entrySet()) {
			if (!isAuthor(e.getKey()) || pinOf(e.getValue()) == null) continue;
			keys.add(e.getKey());
			counters.add(counterOf(e.getValue()));
		}
		int excess = keys.size() - max;
		if (excess <= 0) return;
		Set<String> keep = held.authors();
		List<Integer> order = new ArrayList<>(keys.size());
		for (int i = 0; i < keys.size(); i++) order.add(i);
		order.sort((a, b) -> Long.compare(counters.get(a), counters.get(b)));
		List<String> drop = new ArrayList<>();
		for (int i : order) {
			if (drop.size() >= excess) break;
			if (keep.contains(keys.get(i))) continue;
			drop.add(keys.get(i));
		}
		settings.deleteSettings(ns, drop);
	}

	private static boolean isAuthor(String key) {
		if (key.length() != AUTHOR_KEY_CHARS) return false;
		for (int i = 0; i < key.length(); i++) {
			char c = key.charAt(i);
			if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) return false;
		}
		return true;
	}

	@Nullable
	private static byte[] pinOf(@Nullable String value) {
		if (value == null) return null;
		int colon = value.indexOf(':');
		if (colon <= 0) return null;
		byte[] pin = ChannelOnionStore.unhex(value.substring(colon + 1));
		return pin == null || pin.length != PIN_BYTES ? null : pin;
	}

	private static long counterOf(@Nullable String value) {
		if (value == null) return 0L;
		int colon = value.indexOf(':');
		String digits = colon < 0 ? value : value.substring(0, colon);
		try {
			long n = Long.parseLong(digits);
			return n < 0L ? 0L : n;
		} catch (NumberFormatException e) {
			return 0L;
		}
	}
}
