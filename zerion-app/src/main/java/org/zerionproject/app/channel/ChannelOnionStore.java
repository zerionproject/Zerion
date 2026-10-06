package org.zerionproject.app.channel;

import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class ChannelOnionStore {

	static final String NS = "zerion-channels-onions";
	private static final String NS_REMEMBERED =
			"zerion-channels-remembered-onions";

	private final SettingsManager settings;
	private final BdfReaderFactory readerFactory;
	private final BdfWriterFactory writerFactory;

	ChannelOnionStore(SettingsManager settings,
			BdfReaderFactory readerFactory, BdfWriterFactory writerFactory) {
		this.settings = settings;
		this.readerFactory = readerFactory;
		this.writerFactory = writerFactory;
	}

	static final class Retiring {
		final String onion;
		final String privateKey;
		final long retireAtMs;

		Retiring(String onion, String privateKey, long retireAtMs) {
			this.onion = onion;
			this.privateKey = privateKey;
			this.retireAtMs = retireAtMs;
		}
	}

	static final class Record {
		long nextRotationMs;
		boolean deleted;
		final List<Retiring> retiring = new ArrayList<>();
	}

	synchronized Record get(byte[] channelId) throws DbException {
		String v = settings.getSettings(NS).get(ChannelStore.hex(channelId));
		Record r = new Record();
		if (v == null || v.isEmpty()) return r;
		try {
			BdfReader reader = readerFactory.createReader(
					new ByteArrayInputStream(
							java.util.Base64.getDecoder().decode(v)));
			BdfDictionary d = reader.readDictionary();
			r.nextRotationMs = d.getLong("next", 0L);
			r.deleted = d.getBoolean("deleted", false);
			for (Object o : d.getList("retiring", new BdfList())) {
				BdfList e = (BdfList) o;
				r.retiring.add(new Retiring(e.getString(0), e.getString(1),
						e.getLong(2)));
			}
		} catch (IOException | IllegalArgumentException
				| ClassCastException e) {
			return new Record();
		}
		return r;
	}

	synchronized void put(byte[] channelId, Record r) throws DbException {
		if (r.nextRotationMs == 0L && r.retiring.isEmpty() && !r.deleted) {
			remove(channelId);
			return;
		}
		settings.mergeSettings(encode(channelId, r), NS);
	}

	Settings encode(byte[] channelId, Record r) throws DbException {
		BdfDictionary d = new BdfDictionary();
		d.put("next", r.nextRotationMs);
		d.put("deleted", r.deleted);
		BdfList list = new BdfList();
		for (Retiring e : r.retiring) {
			BdfList entry = new BdfList();
			entry.add(e.onion);
			entry.add(e.privateKey);
			entry.add(e.retireAtMs);
			list.add(entry);
		}
		d.put("retiring", list);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = writerFactory.createWriter(out);
		try {
			w.writeDictionary(d);
			w.flush();
		} catch (IOException e) {
			throw new DbException(e);
		}
		Settings s = new Settings();
		s.put(ChannelStore.hex(channelId), java.util.Base64.getEncoder()
				.withoutPadding().encodeToString(out.toByteArray()));
		return s;
	}

	synchronized List<String> remembered(byte[] channelId)
			throws DbException {
		String v = settings.getSettings(NS_REMEMBERED)
				.get(ChannelStore.hex(channelId));
		List<String> out = new ArrayList<>();
		if (v == null || v.isEmpty()) return out;
		for (String onion : v.split(",")) {
			if (!onion.isEmpty()) out.add(onion);
		}
		return out;
	}

	synchronized void remember(byte[] channelId, String onion)
			throws DbException {
		if (onion.isEmpty()) return;
		List<String> list = remembered(channelId);
		list.remove(onion);
		list.add(0, onion);
		while (list.size() > ChannelConstants.MAX_REMEMBERED_ONIONS) {
			list.remove(list.size() - 1);
		}
		StringBuilder sb = new StringBuilder();
		for (String o : list) {
			if (sb.length() > 0) sb.append(',');
			sb.append(o);
		}
		Settings s = new Settings();
		s.put(ChannelStore.hex(channelId), sb.toString());
		settings.mergeSettings(s, NS_REMEMBERED);
	}

	synchronized List<String> channels() throws DbException {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, String> e
				: settings.getSettings(NS).entrySet()) {
			if (!e.getValue().isEmpty()) out.add(e.getKey());
		}
		return out;
	}

	synchronized void remove(byte[] channelId) throws DbException {
		settings.deleteSettings(NS,
				Collections.singletonList(ChannelStore.hex(channelId)));
	}

	synchronized void forgetRemembered(byte[] channelId) throws DbException {
		settings.deleteSettings(NS_REMEMBERED,
				Collections.singletonList(ChannelStore.hex(channelId)));
	}

	@Nullable
	static byte[] unhex(String hex) {
		if (hex.length() % 2 != 0) return null;
		byte[] out = new byte[hex.length() / 2];
		for (int i = 0; i < out.length; i++) {
			int hi = Character.digit(hex.charAt(2 * i), 16);
			int lo = Character.digit(hex.charAt(2 * i + 1), 16);
			if (hi < 0 || lo < 0) return null;
			out[i] = (byte) ((hi << 4) | lo);
		}
		return out;
	}
}
