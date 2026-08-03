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

	void putSubscriber(byte[] channelId, ChannelSubscriber sub)
			throws DbException {
		List<ChannelSubscriber> existing = getSubscribers(channelId);
		List<ChannelSubscriber> out = new ArrayList<>(existing.size() + 1);
		boolean replaced = false;
		for (ChannelSubscriber s : existing) {
			if (Arrays.equals(s.getEd25519PubKey(),
					sub.getEd25519PubKey())) {
				out.add(sub);
				replaced = true;
			} else {
				out.add(s);
			}
		}
		if (!replaced) out.add(sub);
		write(channelId, out);
	}

	void setBanned(byte[] channelId, byte[] ed25519PubKey, boolean banned)
			throws DbException {
		List<ChannelSubscriber> existing = getSubscribers(channelId);
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
		for (ChannelSubscriber s : getSubscribers(channelId)) {
			if (Arrays.equals(s.getEd25519PubKey(), ed25519PubKey)) {
				return s.isBanned();
			}
		}
		return false;
	}

	void removeAll(byte[] channelId) throws DbException {
		write(channelId, new ArrayList<>());
	}

	private void write(byte[] channelId, List<ChannelSubscriber> subs)
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
		Settings out = new Settings();
		out.put(ChannelStore.hex(channelId),
				encodeBase64(listToBytes(list)));
		settingsManager.mergeSettings(out, NS);
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
