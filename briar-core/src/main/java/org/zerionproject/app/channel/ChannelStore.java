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
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nullable;
import javax.inject.Inject;

@NotNullByDefault
class ChannelStore {

	private static final String NS_STATE = "zerion-channels-state";
	private static final String NS_POSTS = "zerion-channels-posts";
	private static final String NS_PRIV = "zerion-channels-priv";
	private static final String NS_UNREAD = "zerion-channels-unread";
	private static final String NS_MIRROR = "zerion-channels-mirror";
	private static final String NS_INDEX = "zerion-channels-index";
	private static final String INDEX_KEY = "channelIds";

	private final SettingsManager settingsManager;
	private final BdfReaderFactory readerFactory;
	private final BdfWriterFactory writerFactory;

	@Inject
	ChannelStore(SettingsManager settingsManager,
			BdfReaderFactory readerFactory,
			BdfWriterFactory writerFactory) {
		this.settingsManager = settingsManager;
		this.readerFactory = readerFactory;
		this.writerFactory = writerFactory;
	}

	static String hex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) {
			sb.append(String.format(Locale.US, "%02x", x));
		}
		return sb.toString();
	}

	void putChannel(ChannelState s) throws DbException {
		BdfDictionary d = stateToDict(s);
		String encoded = encodeBase64(dictToBytes(d));
		Settings out = new Settings();
		out.put(hex(s.getChannelId()), encoded);
		settingsManager.mergeSettings(out, NS_STATE);
		addToIndex(hex(s.getChannelId()));
	}

	@Nullable
	ChannelState getChannel(byte[] channelId) throws DbException {
		Settings s = settingsManager.getSettings(NS_STATE);
		String encoded = s.get(hex(channelId));
		if (encoded == null) return null;
		try {
			BdfDictionary d = bytesToDict(decodeBase64(encoded));
			return dictToState(d);
		} catch (IOException e) {
			return null;
		}
	}

	Collection<ChannelState> listChannels() throws DbException {
		Set<String> ids = readIndex();
		Settings s = settingsManager.getSettings(NS_STATE);
		List<ChannelState> out = new ArrayList<>(ids.size());
		for (String id : ids) {
			String encoded = s.get(id);
			if (encoded == null) continue;
			try {
				BdfDictionary d = bytesToDict(decodeBase64(encoded));
				out.add(dictToState(d));
			} catch (IOException ignored) {
			}
		}
		return out;
	}

	void removeChannel(byte[] channelId) throws DbException {
		String key = hex(channelId);
		clearKey(NS_STATE, key);
		clearKey(NS_POSTS, key);
		clearKey(NS_PRIV, key);
		clearKey(NS_UNREAD, key);
		clearKey(NS_MIRROR, key);
		removeFromIndex(key);
	}

	void appendPost(byte[] channelId, ChannelPost post)
			throws DbException {
		List<ChannelPost> existing = getPosts(channelId);
		existing.add(post);
		writePosts(channelId, existing);
	}

	List<ChannelPost> getPosts(byte[] channelId) throws DbException {
		Settings s = settingsManager.getSettings(NS_POSTS);
		String encoded = s.get(hex(channelId));
		if (encoded == null) return new ArrayList<>();
		try {
			BdfList list = bytesToList(decodeBase64(encoded));
			List<ChannelPost> out = new ArrayList<>(list.size());
			for (Object o : list) {
				if (o instanceof BdfDictionary) {
					out.add(dictToPost(channelId, (BdfDictionary) o));
				}
			}
			return out;
		} catch (IOException e) {
			return new ArrayList<>();
		}
	}

	void writePosts(byte[] channelId, List<ChannelPost> posts)
			throws DbException {
		BdfList list = new BdfList();
		for (ChannelPost p : posts) {
			list.add(postToDict(p));
		}
		String encoded = encodeBase64(listToBytes(list));
		Settings out = new Settings();
		out.put(hex(channelId), encoded);
		settingsManager.mergeSettings(out, NS_POSTS);
	}

	void putPublisherPrivKey(byte[] channelId, byte[] hybridPriv)
			throws DbException {
		Settings out = new Settings();
		out.put(hex(channelId), encodeBase64(hybridPriv));
		settingsManager.mergeSettings(out, NS_PRIV);
	}

	@Nullable
	byte[] getPublisherPrivKey(byte[] channelId) throws DbException {
		Settings s = settingsManager.getSettings(NS_PRIV);
		String encoded = s.get(hex(channelId));
		if (encoded == null) return null;
		return decodeBase64(encoded);
	}

	int getUnread(byte[] channelId) throws DbException {
		Settings s = settingsManager.getSettings(NS_UNREAD);
		return s.getInt(hex(channelId), 0);
	}

	void setUnread(byte[] channelId, int count) throws DbException {
		Settings out = new Settings();
		out.putInt(hex(channelId), Math.max(0, count));
		settingsManager.mergeSettings(out, NS_UNREAD);
	}

	boolean isMirrorOptedIn(byte[] channelId) throws DbException {
		Settings s = settingsManager.getSettings(NS_MIRROR);
		return s.getBoolean(hex(channelId), false);
	}

	void setMirrorOptedIn(byte[] channelId, boolean mirror)
			throws DbException {
		Settings out = new Settings();
		out.putBoolean(hex(channelId), mirror);
		settingsManager.mergeSettings(out, NS_MIRROR);
	}

	private void clearKey(String namespace, String key)
			throws DbException {
		Settings cur = settingsManager.getSettings(namespace);
		if (!cur.containsKey(key)) return;
		Settings out = new Settings();
		out.put(key, "");
		settingsManager.mergeSettings(out, namespace);
	}

	private Set<String> readIndex() throws DbException {
		Settings s = settingsManager.getSettings(NS_INDEX);
		String csv = s.get(INDEX_KEY);
		Set<String> out = new HashSet<>();
		if (csv == null || csv.isEmpty()) return out;
		for (String id : csv.split(",")) {
			if (!id.isEmpty()) out.add(id);
		}
		return out;
	}

	private void writeIndex(Set<String> ids) throws DbException {
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (String id : ids) {
			if (!first) sb.append(',');
			sb.append(id);
			first = false;
		}
		Settings out = new Settings();
		out.put(INDEX_KEY, sb.toString());
		settingsManager.mergeSettings(out, NS_INDEX);
	}

	private void addToIndex(String channelIdHex) throws DbException {
		Set<String> ids = readIndex();
		if (ids.add(channelIdHex)) writeIndex(ids);
	}

	private void removeFromIndex(String channelIdHex) throws DbException {
		Set<String> ids = readIndex();
		if (ids.remove(channelIdHex)) writeIndex(ids);
	}

	private BdfDictionary stateToDict(ChannelState s) {
		BdfDictionary d = new BdfDictionary();
		d.put("channelId", s.getChannelId());
		d.put("salt", s.getSalt());
		d.put("publisherEd25519", s.getPublisherEd25519PubKey());
		d.put("publisherMlDsa", s.getPublisherMlDsaPubKey());
		d.put("name", s.getName());
		d.put("description", s.getDescription());
		if (s.getAvatarHash() != null) d.put("avatarHash", s.getAvatarHash());
		d.put("createdAtHourMs", s.getCreatedAtHourMs());
		d.put("publicChannel", s.isPublicChannel());
		if (s.getJoinCapability() != null) {
			d.put("joinCapability", s.getJoinCapability());
		}
		d.put("currentOnion", s.getCurrentOnion());
		d.put("manifestSeq", s.getManifestSeq());
		d.put("weArePublisher", s.weArePublisher());
		d.put("highestKnownPostSeq", s.getHighestKnownPostSeq());
		if (s.getContentKeyHash() != null) {
			d.put("contentKeyHash", s.getContentKeyHash());
		}
		if (s.getContentKey() != null) {
			d.put("contentKey", s.getContentKey());
		}
		BdfList delegList = new BdfList();
		for (ChannelDelegationCert c : s.getActiveDelegations()) {
			BdfDictionary cd = new BdfDictionary();
			cd.put("channelId", c.getChannelId());
			cd.put("delegateeEd25519", c.getDelegateeEd25519PubKey());
			cd.put("delegateeMlDsa", c.getDelegateeMlDsaPubKey());
			cd.put("validFromHourMs", c.getValidFromHourMs());
			cd.put("validUntilHourMs", c.getValidUntilHourMs());
			cd.put("delegationSeq", c.getDelegationSeq());
			cd.put("signature", c.getSignature());
			delegList.add(cd);
		}
		d.put("activeDelegations", delegList);
		BdfList revokedList = new BdfList();
		for (Long seq : s.getRevokedDelegationSeqs()) revokedList.add(seq);
		d.put("revokedDelegationSeqs", revokedList);
		d.put("nextDelegationSeq", s.getNextDelegationSeq());
		if (s.getOnionPrivateKey() != null) {
			d.put("onionPrivateKey", s.getOnionPrivateKey());
		}
		d.put("pinnedPostSeq", s.getPinnedPostSeq());
		d.put("requiresApproval", s.requiresApproval());
		return d;
	}

	private ChannelState dictToState(BdfDictionary d) throws FormatException {
		List<ChannelDelegationCert> active = new ArrayList<>();
		BdfList rawActive = d.getList("activeDelegations",
				new BdfList());
		for (Object o : rawActive) {
			if (!(o instanceof BdfDictionary)) continue;
			BdfDictionary cd = (BdfDictionary) o;
			active.add(new ChannelDelegationCert(
					cd.getRaw("channelId"),
					cd.getRaw("delegateeEd25519"),
					cd.getRaw("delegateeMlDsa"),
					cd.getLong("validFromHourMs"),
					cd.getLong("validUntilHourMs"),
					cd.getLong("delegationSeq"),
					cd.getRaw("signature")));
		}
		List<Long> revoked = new ArrayList<>();
		BdfList rawRevoked = d.getList("revokedDelegationSeqs",
				new BdfList());
		for (Object o : rawRevoked) {
			if (o instanceof Long) revoked.add((Long) o);
		}
		return new ChannelState(
				d.getRaw("channelId"),
				d.getRaw("salt"),
				d.getRaw("publisherEd25519"),
				d.getRaw("publisherMlDsa"),
				d.getString("name"),
				d.getString("description"),
				d.getOptionalRaw("avatarHash"),
				d.getLong("createdAtHourMs"),
				d.getBoolean("publicChannel"),
				d.getOptionalRaw("joinCapability"),
				d.getString("currentOnion"),
				d.getLong("manifestSeq"),
				d.getBoolean("weArePublisher"),
				d.getLong("highestKnownPostSeq"),
				d.getOptionalRaw("contentKeyHash"),
				d.getOptionalRaw("contentKey"),
				active,
				revoked,
				d.getLong("nextDelegationSeq", 0L),
				d.getOptionalString("onionPrivateKey"),
				d.getLong("pinnedPostSeq",
						ChannelState.NO_PINNED_POST),
				d.getBoolean("requiresApproval", false));
	}

	private BdfDictionary postToDict(ChannelPost p) {
		BdfDictionary d = new BdfDictionary();
		d.put("seqNum", p.getSeqNum());
		d.put("prevHash", p.getPrevHash());
		d.put("timestampHourMs", p.getTimestampHourMs());
		d.put("body", p.getBody());
		d.put("ttlMs", p.getTtlMs());
		d.put("signature", p.getSignature());
		d.put("read", p.isRead());
		BdfList atts = new BdfList();
		for (ChannelPost.ChannelAttachment a : p.getAttachments()) {
			BdfDictionary ad = new BdfDictionary();
			ad.put("hash", a.getBlobHash());
			ad.put("size", a.getSizeBytes());
			ad.put("mime", a.getMimeType());
			ad.put("key", a.getPerAttachmentKey());
			if (a.getCaptionUtf8() != null) {
				ad.put("caption", a.getCaptionUtf8());
			}
			if (a.getThumbnail() != null) {
				ad.put("thumb", a.getThumbnail());
			}
			atts.add(ad);
		}
		d.put("attachments", atts);
		if (p.getDelegateSignerEd25519PubKey() != null) {
			d.put("delegateSignerEd25519",
					p.getDelegateSignerEd25519PubKey());
		}
		if (p.getDelegateSignerMlDsaPubKey() != null) {
			d.put("delegateSignerMlDsa",
					p.getDelegateSignerMlDsaPubKey());
		}
		return d;
	}

	private ChannelPost dictToPost(byte[] channelId, BdfDictionary d)
			throws FormatException {
		List<ChannelPost.ChannelAttachment> atts = new ArrayList<>();
		BdfList raw = d.getList("attachments");
		for (Object o : raw) {
			if (!(o instanceof BdfDictionary)) continue;
			BdfDictionary ad = (BdfDictionary) o;
			atts.add(new ChannelPost.ChannelAttachment(
					ad.getRaw("hash"),
					ad.getLong("size"),
					ad.getString("mime"),
					ad.getRaw("key"),
					ad.getOptionalString("caption"),
					ad.getOptionalRaw("thumb")));
		}
		return new ChannelPost(channelId,
				d.getLong("seqNum"),
				d.getRaw("prevHash"),
				d.getLong("timestampHourMs"),
				d.getString("body"),
				atts,
				d.getLong("ttlMs"),
				d.getRaw("signature"),
				d.getBoolean("read"),
				d.getOptionalRaw("delegateSignerEd25519"),
				d.getOptionalRaw("delegateSignerMlDsa"));
	}

	private byte[] dictToBytes(BdfDictionary d) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = writerFactory.createWriter(out);
		try {
			w.writeDictionary(d);
			w.flush();
		} catch (IOException e) {
			return new byte[0];
		}
		return out.toByteArray();
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

	private BdfDictionary bytesToDict(byte[] bytes)
			throws FormatException, IOException {
		BdfReader r = readerFactory.createReader(
				new ByteArrayInputStream(bytes));
		return r.readDictionary();
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
