package org.briarproject.briar.channel;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.BdfReader;
import org.briarproject.bramble.api.data.BdfReaderFactory;
import org.briarproject.bramble.api.data.BdfWriter;
import org.briarproject.bramble.api.data.BdfWriterFactory;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.settings.Settings;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.briar.api.channel.ChannelReaction;
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
class ChannelReactionStore {

	private static final String NS = "zerion-channels-reactions";

	private final SettingsManager settingsManager;
	private final BdfReaderFactory readerFactory;
	private final BdfWriterFactory writerFactory;

	@Inject
	ChannelReactionStore(SettingsManager settingsManager,
			BdfReaderFactory readerFactory,
			BdfWriterFactory writerFactory) {
		this.settingsManager = settingsManager;
		this.readerFactory = readerFactory;
		this.writerFactory = writerFactory;
	}

	List<ChannelReaction> getReactions(byte[] channelId) throws DbException {
		Settings s = settingsManager.getSettings(NS);
		String encoded = s.get(ChannelStore.hex(channelId));
		if (encoded == null) return new ArrayList<>();
		try {
			BdfList list = bytesToList(decodeBase64(encoded));
			List<ChannelReaction> out = new ArrayList<>(list.size());
			for (Object o : list) {
				if (!(o instanceof BdfDictionary)) continue;
				BdfDictionary d = (BdfDictionary) o;
				out.add(new ChannelReaction(
						d.getLong("seq"),
						d.getString("emoji"),
						d.getRaw("ed"),
						d.getRaw("ml"),
						d.getLong("ts")));
			}
			return out;
		} catch (IOException e) {
			return new ArrayList<>();
		}
	}

	void putReaction(byte[] channelId, ChannelReaction reaction)
			throws DbException {
		List<ChannelReaction> existing = getReactions(channelId);
		List<ChannelReaction> out = new ArrayList<>(existing.size() + 1);
		boolean replaced = false;
		for (ChannelReaction r : existing) {
			if (r.getPostSeqNum() == reaction.getPostSeqNum()
					&& Arrays.equals(r.getSignerEd25519PubKey(),
							reaction.getSignerEd25519PubKey())) {
				out.add(reaction);
				replaced = true;
			} else {
				out.add(r);
			}
		}
		if (!replaced) out.add(reaction);
		write(channelId, out);
	}

	void removeForPost(byte[] channelId, long postSeqNum)
			throws DbException {
		List<ChannelReaction> existing = getReactions(channelId);
		List<ChannelReaction> out = new ArrayList<>(existing.size());
		for (ChannelReaction r : existing) {
			if (r.getPostSeqNum() != postSeqNum) out.add(r);
		}
		write(channelId, out);
	}

	void removeAll(byte[] channelId) throws DbException {
		write(channelId, new ArrayList<>());
	}

	private void write(byte[] channelId, List<ChannelReaction> reactions)
			throws DbException {
		BdfList list = new BdfList();
		for (ChannelReaction r : reactions) {
			BdfDictionary d = new BdfDictionary();
			d.put("seq", r.getPostSeqNum());
			d.put("emoji", r.getEmoji());
			d.put("ed", r.getSignerEd25519PubKey());
			d.put("ml", r.getSignerMlDsaPubKey());
			d.put("ts", r.getTimestampHourMs());
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
