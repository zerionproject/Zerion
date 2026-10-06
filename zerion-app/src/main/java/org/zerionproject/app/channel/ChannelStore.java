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
import org.zerionproject.app.api.channel.ChannelConstants;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.inject.Inject;

@NotNullByDefault
class ChannelStore {

	private static final String NS_STATE = "zerion-channels-state";
	private static final String NS_PRIV = "zerion-channels-priv";
	private static final String NS_UNREAD = "zerion-channels-unread";
	private static final String NS_MIRROR = "zerion-channels-mirror";
	private static final String NS_INDEX = "zerion-channels-index";
	private static final String INDEX_KEY = "channelIds";
	private static final String NS_PENDING_REMOVAL =
			"zerion-channels-pending-removal";
	private static final String REMOVAL_KEEPS_TOMBSTONE = "t";
	private static final String REMOVAL_COMPLETE = "c";
	private static final String NS_INSTANCE = "zerion-channels-instance";
	private static final String INSTANCE_KEY = "id";
	private static final String NS_RETIRED_CONTENT_KEYS =
			"zerion-channels-retired-content-keys";
	static final int MAX_RETIRED_CONTENT_KEYS = 64;

	private final SettingsManager settingsManager;
	private final BdfReaderFactory readerFactory;
	private final BdfWriterFactory writerFactory;
	private final ChannelPostStore posts;
	private final ChannelOnionStore onions;

	@Inject
	ChannelStore(SettingsManager settingsManager,
			BdfReaderFactory readerFactory,
			BdfWriterFactory writerFactory) {
		this.settingsManager = settingsManager;
		this.readerFactory = readerFactory;
		this.writerFactory = writerFactory;
		this.posts = new ChannelPostStore(settingsManager, readerFactory,
				writerFactory);
		this.onions = new ChannelOnionStore(settingsManager, readerFactory,
				writerFactory);
	}

	ChannelOnionStore onions() {
		return onions;
	}

	ChannelPostStore posts() {
		return posts;
	}

	SettingsManager settings() {
		return settingsManager;
	}

	BdfReaderFactory readerFactory() {
		return readerFactory;
	}

	BdfWriterFactory writerFactory() {
		return writerFactory;
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

	void putChannel(ChannelState s, Map<String, Settings> alsoWrite)
			throws DbException {
		Map<String, Settings> batch = new LinkedHashMap<>(alsoWrite);
		Settings state = new Settings();
		state.put(hex(s.getChannelId()),
				encodeBase64(dictToBytes(stateToDict(s))));
		batch.put(NS_STATE, state);
		Set<String> ids = readIndex();
		if (ids.add(hex(s.getChannelId()))) {
			batch.put(NS_INDEX, indexSettings(ids));
		}
		settingsManager.mergeSettings(batch);
	}

	static Settings pendingRemoval(byte[] channelId, boolean keepTombstone) {
		Settings out = new Settings();
		out.put(hex(channelId), keepTombstone ? REMOVAL_KEEPS_TOMBSTONE
				: REMOVAL_COMPLETE);
		return out;
	}

	static String pendingRemovalNamespace() {
		return NS_PENDING_REMOVAL;
	}

	void markPendingRemoval(byte[] channelId, boolean keepTombstone)
			throws DbException {
		settingsManager.mergeSettings(pendingRemoval(channelId,
				keepTombstone), NS_PENDING_REMOVAL);
	}

	Map<String, Boolean> pendingRemovals() throws DbException {
		Map<String, Boolean> out = new LinkedHashMap<>();
		for (Map.Entry<String, String> e
				: settingsManager.getSettings(NS_PENDING_REMOVAL).entrySet()) {
			if (e.getValue().isEmpty()) continue;
			out.put(e.getKey(), REMOVAL_KEEPS_TOMBSTONE.equals(e.getValue()));
		}
		return out;
	}

	void clearPendingRemoval(byte[] channelId) throws DbException {
		settingsManager.deleteSettings(NS_PENDING_REMOVAL,
				java.util.Collections.singletonList(hex(channelId)));
	}

	@Nullable
	String getInstanceId() throws DbException {
		String v = settingsManager.getSettings(NS_INSTANCE).get(INSTANCE_KEY);
		return v == null || v.isEmpty() ? null : v;
	}

	void setInstanceId(String id) throws DbException {
		Settings out = new Settings();
		out.put(INSTANCE_KEY, id);
		settingsManager.mergeSettings(out, NS_INSTANCE);
	}

	@Nullable
	ChannelState getChannel(byte[] channelId) throws DbException {
		Settings s = settingsManager.getSettings(NS_STATE);
		String encoded = s.get(hex(channelId));
		if (encoded == null) return null;
		try {
			BdfDictionary d = bytesToDict(decodeBase64(encoded));
			return dictToState(d);
		} catch (IOException | IllegalArgumentException e) {
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
			} catch (IOException | IllegalArgumentException ignored) {
			}
		}
		return out;
	}

	void removeChannel(byte[] channelId) throws DbException {
		String key = hex(channelId);
		posts.removeAll(channelId);
		java.util.List<String> keys = java.util.Collections.singletonList(key);
		settingsManager.deleteSettings(NS_STATE, keys);
		settingsManager.deleteSettings(NS_PRIV, keys);
		settingsManager.deleteSettings(NS_UNREAD, keys);
		settingsManager.deleteSettings(NS_MIRROR, keys);
		settingsManager.deleteSettings(NS_RETIRED_CONTENT_KEYS, keys);
		removeFromIndex(key);
	}

	List<byte[]> getRetiredContentKeys(byte[] channelId) throws DbException {
		String stored = settingsManager.getSetting(NS_RETIRED_CONTENT_KEYS,
				hex(channelId));
		List<byte[]> out = new ArrayList<>();
		if (stored == null || stored.isEmpty()) return out;
		for (String encoded : stored.split(",")) {
			try {
				byte[] key = decodeBase64(encoded);
				if (key.length == ChannelConstants.CONTENT_KEY_BYTES) {
					out.add(key);
				}
			} catch (IllegalArgumentException ignored) {
			}
		}
		return out;
	}

	Map<String, Settings> retireContentKey(byte[] channelId,
			byte[] contentKey) throws DbException {
		List<byte[]> keys = getRetiredContentKeys(channelId);
		keys.add(0, contentKey);
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < keys.size() && i < MAX_RETIRED_CONTENT_KEYS;
				i++) {
			if (i > 0) sb.append(',');
			sb.append(encodeBase64(keys.get(i)));
		}
		Settings out = new Settings();
		out.put(hex(channelId), sb.toString());
		Map<String, Settings> batch = new LinkedHashMap<>();
		batch.put(NS_RETIRED_CONTENT_KEYS, out);
		return batch;
	}

	void appendPost(byte[] channelId, ChannelPost post)
			throws DbException {
		posts.append(channelId, post, null);
	}

	List<ChannelPost> getPosts(byte[] channelId) throws DbException {
		return posts.getPosts(channelId);
	}

	void writePosts(byte[] channelId, List<ChannelPost> list)
			throws DbException {
		posts.replaceAll(channelId, list);
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
		settingsManager.mergeSettings(indexSettings(ids), NS_INDEX);
	}

	private static Settings indexSettings(Set<String> ids) {
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (String id : ids) {
			if (!first) sb.append(',');
			sb.append(id);
			first = false;
		}
		Settings out = new Settings();
		out.put(INDEX_KEY, sb.toString());
		return out;
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
		BdfList retiredList = new BdfList();
		for (ChannelDelegationCert c : s.getRetiredDelegations()) {
			BdfDictionary cd = new BdfDictionary();
			cd.put("channelId", c.getChannelId());
			cd.put("delegateeEd25519", c.getDelegateeEd25519PubKey());
			cd.put("delegateeMlDsa", c.getDelegateeMlDsaPubKey());
			cd.put("validFromHourMs", c.getValidFromHourMs());
			cd.put("validUntilHourMs", c.getValidUntilHourMs());
			cd.put("delegationSeq", c.getDelegationSeq());
			cd.put("signature", c.getSignature());
			retiredList.add(cd);
		}
		d.put("retiredDelegations", retiredList);
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
		List<ChannelDelegationCert> retired = new ArrayList<>();
		BdfList rawRetired = d.getList("retiredDelegations",
				new BdfList());
		for (Object o : rawRetired) {
			if (!(o instanceof BdfDictionary)) continue;
			BdfDictionary cd = (BdfDictionary) o;
			retired.add(new ChannelDelegationCert(
					cd.getRaw("channelId"),
					cd.getRaw("delegateeEd25519"),
					cd.getRaw("delegateeMlDsa"),
					cd.getLong("validFromHourMs"),
					cd.getLong("validUntilHourMs"),
					cd.getLong("delegationSeq"),
					cd.getRaw("signature")));
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
				d.getBoolean("requiresApproval", false),
				retired);
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

	private BdfDictionary bytesToDict(byte[] bytes)
			throws FormatException, IOException {
		BdfReader r = readerFactory.createReader(
				new ByteArrayInputStream(bytes));
		return r.readDictionary();
	}

	private static String encodeBase64(byte[] data) {
		return java.util.Base64.getEncoder()
				.withoutPadding().encodeToString(data);
	}

	private static byte[] decodeBase64(String s) {
		return java.util.Base64.getDecoder().decode(s);
	}
}
