package org.zerionproject.app.channel;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class ChannelSubscriberStore {

	private static final String NS = "zerion-channels-subscribers";
	private static final String NS_BANS = "zerion-channels-bans";
	private static final String NS_TRUSTED = "zerion-channels-trusted";

	private final SettingsManager settingsManager;
	private final BdfReaderFactory readerFactory;
	private final BdfWriterFactory writerFactory;

	@Inject
	ChannelSubscriberStore(SettingsManager settingsManager,
			BdfReaderFactory readerFactory,
			BdfWriterFactory writerFactory) {
		this.settingsManager = settingsManager;
		this.readerFactory = readerFactory;
		this.writerFactory = writerFactory;
	}

	List<ChannelSubscriber> getSubscribers(byte[] channelId)
			throws DbException {
		List<ChannelSubscriber> stored = storedSubscribers(channelId);
		List<byte[]> trusted = getTrusted(channelId);
		if (trusted.isEmpty()) return stored;
		List<ChannelSubscriber> out = new ArrayList<>(stored.size());
		for (ChannelSubscriber sub : stored) {
			boolean t = contains(trusted, sub.getEd25519PubKey());
			out.add(t ? new ChannelSubscriber(sub.getDisplayName(),
					sub.getEd25519PubKey(), sub.getMlDsaPubKey(),
					sub.getJoinedAtHourMs(), sub.isBanned(), true) : sub);
		}
		return out;
	}

	private static boolean contains(List<byte[]> keys, byte[] key) {
		for (byte[] k : keys) {
			if (Arrays.equals(k, key)) return true;
		}
		return false;
	}

	void setTrusted(byte[] channelId, byte[] ed25519PubKey, boolean trusted)
			throws DbException {
		List<byte[]> keys = getTrusted(channelId);
		List<byte[]> next = new ArrayList<>(keys.size() + 1);
		for (byte[] k : keys) {
			if (!Arrays.equals(k, ed25519PubKey)) next.add(k);
		}
		if (trusted) next.add(ed25519PubKey);
		while (next.size() > org.zerionproject.app.api.channel
				.ChannelConstants.MAX_BANNED_KEYS_PER_CHANNEL) {
			next.remove(0);
		}
		writeKeys(NS_TRUSTED, channelId, next);
	}

	List<byte[]> getTrusted(byte[] channelId) throws DbException {
		return readKeys(NS_TRUSTED, channelId);
	}

	private List<ChannelSubscriber> storedSubscribers(byte[] channelId)
			throws DbException {
		Settings s = settingsManager.getSettings(NS);
		String encoded = s.get(ChannelStore.hex(channelId));
		if (encoded == null) return new ArrayList<>();
		try {
			BdfList list = bytesToList(decodeBase64(encoded));
			List<ChannelSubscriber> out = new ArrayList<>(list.size());
			for (Object o : list) {
				if (!(o instanceof BdfDictionary)) continue;
				BdfDictionary d = (BdfDictionary) o;
				out.add(new ChannelSubscriber(
						d.getString("name"),
						d.getRaw("ed"),
						d.getRaw("ml"),
						d.getLong("ts"),
						d.getBoolean("banned", false)));
			}
			return out;
		} catch (IOException e) {
			return new ArrayList<>();
		}
	}

	long putSubscriber(byte[] channelId, ChannelSubscriber sub)
			throws DbException {
		List<ChannelSubscriber> existing = storedSubscribers(channelId);
		List<ChannelSubscriber> out = new ArrayList<>(existing.size() + 1);
		boolean replaced = false;
		for (ChannelSubscriber s : existing) {
			if (Arrays.equals(s.getEd25519PubKey(),
					sub.getEd25519PubKey())) {
				if (sameSubscriber(s, sub)) return 0L;
				out.add(sub);
				replaced = true;
			} else {
				out.add(s);
			}
		}
		if (!replaced) out.add(sub);
		return write(channelId, out);
	}

	private static boolean sameSubscriber(ChannelSubscriber a,
			ChannelSubscriber b) {
		return a.getDisplayName().equals(b.getDisplayName())
				&& Arrays.equals(a.getEd25519PubKey(), b.getEd25519PubKey())
				&& Arrays.equals(a.getMlDsaPubKey(), b.getMlDsaPubKey())
				&& a.getJoinedAtHourMs() == b.getJoinedAtHourMs()
				&& a.isBanned() == b.isBanned();
	}

	void setBanned(byte[] channelId, byte[] ed25519PubKey, boolean banned)
			throws DbException {
		List<byte[]> bans = getBans(channelId);
		List<byte[]> nextBans = new ArrayList<>(bans.size() + 1);
		for (byte[] k : bans) {
			if (!Arrays.equals(k, ed25519PubKey)) nextBans.add(k);
		}
		if (banned) nextBans.add(ed25519PubKey);
		while (nextBans.size() > org.zerionproject.app.api.channel
				.ChannelConstants.MAX_BANNED_KEYS_PER_CHANNEL) {
			nextBans.remove(0);
		}
		writeKeys(NS_BANS, channelId, nextBans);
		if (banned) setTrusted(channelId, ed25519PubKey, false);
		List<ChannelSubscriber> existing = storedSubscribers(channelId);
		List<ChannelSubscriber> out = new ArrayList<>(existing.size());
		for (ChannelSubscriber s : existing) {
			if (Arrays.equals(s.getEd25519PubKey(), ed25519PubKey)) {
				out.add(new ChannelSubscriber(s.getDisplayName(),
						s.getEd25519PubKey(), s.getMlDsaPubKey(),
						s.getJoinedAtHourMs(), banned));
			} else {
				out.add(s);
			}
		}
		write(channelId, out);
	}

	boolean isBanned(byte[] channelId, byte[] ed25519PubKey)
			throws DbException {
		for (byte[] k : getBans(channelId)) {
			if (Arrays.equals(k, ed25519PubKey)) return true;
		}
		for (ChannelSubscriber s : storedSubscribers(channelId)) {
			if (Arrays.equals(s.getEd25519PubKey(), ed25519PubKey)) {
				return s.isBanned();
			}
		}
		return false;
	}

	List<byte[]> getBans(byte[] channelId) throws DbException {
		return readKeys(NS_BANS, channelId);
	}

	private List<byte[]> readKeys(String namespace, byte[] channelId)
			throws DbException {
		String encoded = settingsManager.getSettings(namespace)
				.get(ChannelStore.hex(channelId));
		List<byte[]> out = new ArrayList<>();
		if (encoded == null || encoded.isEmpty()) return out;
		try {
			for (Object o : bytesToList(decodeBase64(encoded))) {
				if (o instanceof byte[]) out.add((byte[]) o);
			}
		} catch (IOException | IllegalArgumentException e) {
			return out;
		}
		return out;
	}

	private void writeKeys(String namespace, byte[] channelId,
			List<byte[]> keys) throws DbException {
		if (keys.isEmpty()) {
			settingsManager.deleteSettings(namespace, java.util.Collections
					.singletonList(ChannelStore.hex(channelId)));
			return;
		}
		BdfList list = new BdfList();
		list.addAll(keys);
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId),
				encodeBase64(listToBytes(list)));
		settingsManager.mergeSettings(out, namespace);
	}

	void removeAll(byte[] channelId) throws DbException {
		List<String> key = java.util.Collections
				.singletonList(ChannelStore.hex(channelId));
		settingsManager.deleteSettings(NS, key);
		settingsManager.deleteSettings(NS_BANS, key);
		settingsManager.deleteSettings(NS_TRUSTED, key);
	}

	private long write(byte[] channelId, List<ChannelSubscriber> subs)
			throws DbException {
		BdfList list = new BdfList();
		for (ChannelSubscriber s : subs) {
			BdfDictionary d = new BdfDictionary();
			d.put("name", s.getDisplayName());
			d.put("ed", s.getEd25519PubKey());
			d.put("ml", s.getMlDsaPubKey());
			d.put("ts", s.getJoinedAtHourMs());
			d.put("banned", s.isBanned());
			list.add(d);
		}
		String encoded = encodeBase64(listToBytes(list));
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId), encoded);
		settingsManager.mergeSettings(out, NS);
		return encoded.length();
	}

	private byte[] listToBytes(BdfList l) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = writerFactory.createWriter(out);
		try {
			w.writeList(l);
			w.flush();
		} catch (IOException e) {
			return new byte[0];
		}
		return out.toByteArray();
	}

	private BdfList bytesToList(byte[] bytes)
			throws FormatException, IOException {
		BdfReader r = readerFactory.createReader(
				new ByteArrayInputStream(bytes));
		return r.readList();
	}

	private static String encodeBase64(byte[] data) {
		return java.util.Base64.getEncoder()
				.withoutPadding().encodeToString(data);
	}

	private static byte[] decodeBase64(String s) {
		return java.util.Base64.getDecoder().decode(s);
	}
}
