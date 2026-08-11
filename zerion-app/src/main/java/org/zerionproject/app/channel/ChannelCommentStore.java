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
import org.zerionproject.app.api.channel.ChannelComment;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@NotNullByDefault
class ChannelCommentStore {

	private static final String NS = "zerion-channels-comments";

	private final SettingsManager settingsManager;
	private final BdfReaderFactory readerFactory;
	private final BdfWriterFactory writerFactory;

	@Inject
	ChannelCommentStore(SettingsManager settingsManager,
			BdfReaderFactory readerFactory,
			BdfWriterFactory writerFactory) {
		this.settingsManager = settingsManager;
		this.readerFactory = readerFactory;
		this.writerFactory = writerFactory;
	}

	List<ChannelComment> getComments(byte[] channelId) throws DbException {
		Settings s = settingsManager.getSettings(NS);
		String encoded = s.get(ChannelStore.hex(channelId));
		if (encoded == null) return new ArrayList<>();
		try {
			BdfList list = bytesToList(decodeBase64(encoded));
			List<ChannelComment> out = new ArrayList<>(list.size());
			for (Object o : list) {
				if (!(o instanceof BdfDictionary)) continue;
				BdfDictionary d = (BdfDictionary) o;
				byte[] sig = d.getOptionalRaw("sig");
				out.add(new ChannelComment(
						d.getLong("seq"),
						d.getLong("id"),
						d.getString("body"),
						d.getString("name"),
						d.getRaw("ed"),
						d.getRaw("ml"),
						d.getLong("ts"),
						sig == null ? new byte[0] : sig));
			}
			return out;
		} catch (IOException e) {
			return new ArrayList<>();
		}
	}

	boolean putComment(byte[] channelId, ChannelComment c)
			throws DbException {
		List<ChannelComment> existing = getComments(channelId);
		for (ChannelComment ex : existing) {
			if (ex.getCommentId() == c.getCommentId()) return false;
		}
		if (existing.size() >= 4096) return false;
		List<ChannelComment> out = new ArrayList<>(existing.size() + 1);
		out.addAll(existing);
		out.add(c);
		write(channelId, out);
		return true;
	}

	void removeForParent(byte[] channelId, long parentSeqNum)
			throws DbException {
		List<ChannelComment> existing = getComments(channelId);
		List<ChannelComment> out = new ArrayList<>(existing.size());
		for (ChannelComment c : existing) {
			if (c.getParentPostSeqNum() != parentSeqNum) out.add(c);
		}
		write(channelId, out);
	}

	void removeAll(byte[] channelId) throws DbException {
		write(channelId, new ArrayList<>());
	}

	private void write(byte[] channelId, List<ChannelComment> comments)
			throws DbException {
		BdfList list = new BdfList();
		for (ChannelComment c : comments) {
			BdfDictionary d = new BdfDictionary();
			d.put("seq", c.getParentPostSeqNum());
			d.put("id", c.getCommentId());
			d.put("body", c.getBody());
			d.put("name", c.getAuthorDisplayName());
			d.put("ed", c.getAuthorEd25519PubKey());
			d.put("ml", c.getAuthorMlDsaPubKey());
			d.put("ts", c.getTimestampHourMs());
			byte[] sig = c.getSignature();
			if (sig != null && sig.length > 0) d.put("sig", sig);
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
