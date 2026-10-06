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
	private final ChannelItemSync sync;

	@Inject
	ChannelCommentStore(SettingsManager settingsManager,
			BdfReaderFactory readerFactory,
			BdfWriterFactory writerFactory) {
		this.settingsManager = settingsManager;
		this.readerFactory = readerFactory;
		this.writerFactory = writerFactory;
		this.sync = new ChannelItemSync(settingsManager, readerFactory,
				writerFactory, "c");
	}

	ChannelItemSync sync() {
		return sync;
	}

	static String keyOf(ChannelComment c) {
		return Long.toString(c.getCommentId());
	}

	private static byte[] digestOf(ChannelComment c) {
		byte[] sig = c.getSignature();
		if (sig != null && sig.length >= 16) {
			return java.util.Arrays.copyOf(sig, 16);
		}
		return (c.getBody() + ":" + c.getTimestampHourMs())
				.getBytes(java.nio.charset.StandardCharsets.UTF_8);
	}

	List<ChannelComment> getComments(byte[] channelId) throws DbException {
		List<ChannelComment> stored = readStored(channelId);
		List<ChannelComment> fitted =
				ChannelCommentPolicy.fitToCeilings(stored);
		if (fitted != stored) write(channelId, fitted);
		return fitted;
	}

	private List<ChannelComment> readStored(byte[] channelId)
			throws DbException {
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
		return putComment(channelId, c, getComments(channelId));
	}

	boolean putComment(byte[] channelId, ChannelComment c,
			List<ChannelComment> current) throws DbException {
		List<ChannelComment> next =
				ChannelCommentPolicy.withAdmitted(current, c);
		if (next == null || next == current) return false;
		write(channelId, next);
		return true;
	}

	void setComments(byte[] channelId, List<ChannelComment> comments)
			throws DbException {
		write(channelId, ChannelCommentPolicy.fitToCeilings(comments));
	}

	boolean retainPosts(byte[] channelId, java.util.Set<Long> posts)
			throws DbException {
		List<ChannelComment> current = getComments(channelId);
		List<ChannelComment> kept =
				ChannelCommentPolicy.retainPosts(current, posts);
		if (kept == current) return false;
		write(channelId, kept);
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
		settingsManager.deleteSettings(NS, java.util.Collections
				.singletonList(ChannelStore.hex(channelId)));
		sync.removeAll(channelId);
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
		List<String> keys = new ArrayList<>(comments.size());
		List<byte[]> digests = new ArrayList<>(comments.size());
		for (ChannelComment c : comments) {
			keys.add(keyOf(c));
			digests.add(digestOf(c));
		}
		sync.recordWrite(channelId, keys, digests);
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
