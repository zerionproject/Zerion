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
import org.zerionproject.app.api.channel.ChannelPost;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

@NotNullByDefault
final class ChannelPostStore {

	static final String NS_LEGACY_POSTS = "zerion-channels-posts";
	private static final String NS_POST_PREFIX = "zerion-channels-post:";
	private static final String NS_META_PREFIX = "zerion-channels-post-meta:";
	private static final String POST_KEY = "p";
	private static final String META_KEY = "m";
	static final int MIGRATION_CHUNK_POSTS = 64;
	private static final long MIGRATION_CHUNK_CHARS = 1024L * 1024L;
	private static final int ENCODED_LIST_START = 0x60;

	private final SettingsManager settings;
	private final BdfReaderFactory readerFactory;
	private final BdfWriterFactory writerFactory;
	private final Map<String, Object> locks = new ConcurrentHashMap<>();

	ChannelPostStore(SettingsManager settings, BdfReaderFactory readerFactory,
			BdfWriterFactory writerFactory) {
		this.settings = settings;
		this.readerFactory = readerFactory;
		this.writerFactory = writerFactory;
	}

	static final class Meta {

		final List<long[]> held = new ArrayList<>();
		long tipSeq = -1L;
		@Nullable
		byte[] tipHash;
		long count;
		long bytes;
		long readThrough = -1L;
		final TreeSet<Long> withheld = new TreeSet<>();
		long minExpiry = Long.MAX_VALUE;
		long pruned;
		long skipped;
		long lastScanMs;
		long delegateBytes;
		long migrated = -1L;

		boolean holds(long seq) {
			for (long[] r : held) {
				if (seq >= r[0] && seq <= r[1]) return true;
			}
			return false;
		}

		List<Long> heldSeqs() {
			List<Long> out = new ArrayList<>();
			for (long[] r : held) {
				for (long s = r[0]; ; s++) {
					out.add(s);
					if (s >= r[1]) break;
				}
			}
			return out;
		}

		long lowest() {
			return held.isEmpty() ? -1L : held.get(0)[0];
		}

		long highest() {
			return held.isEmpty() ? -1L : held.get(held.size() - 1)[1];
		}

		void add(long seq) {
			List<long[]> next = new ArrayList<>(held.size() + 1);
			boolean placed = false;
			for (long[] r : held) {
				if (!placed && seq < r[0]) {
					next.add(new long[] {seq, seq});
					placed = true;
				}
				next.add(new long[] {r[0], r[1]});
			}
			if (!placed) next.add(new long[] {seq, seq});
			held.clear();
			for (long[] r : next) {
				if (!held.isEmpty()
						&& held.get(held.size() - 1)[1] >= r[0] - 1L) {
					long[] last = held.get(held.size() - 1);
					last[1] = Math.max(last[1], r[1]);
				} else {
					held.add(r);
				}
			}
		}

		void remove(long seq) {
			List<long[]> next = new ArrayList<>(held.size() + 1);
			for (long[] r : held) {
				if (seq < r[0] || seq > r[1]) {
					next.add(r);
					continue;
				}
				if (r[0] < seq) next.add(new long[] {r[0], seq - 1L});
				if (seq < r[1]) next.add(new long[] {seq + 1L, r[1]});
			}
			held.clear();
			held.addAll(next);
			withheld.remove(seq);
		}

		int unread() {
			int n = 0;
			for (long[] r : held) {
				if (r[1] <= readThrough) continue;
				long from = Math.max(r[0], readThrough + 1L);
				for (long s = from; ; s++) {
					if (!withheld.contains(s)) n++;
					if (s >= r[1]) break;
				}
			}
			return n;
		}
	}

	private Object lockFor(byte[] channelId) {
		return locks.computeIfAbsent(ChannelStore.hex(channelId),
				k -> new Object());
	}

	private static String postNamespace(byte[] channelId, long seq) {
		return NS_POST_PREFIX + ChannelStore.hex(channelId) + ":" + seq;
	}

	private static String metaNamespace(byte[] channelId) {
		return NS_META_PREFIX + ChannelStore.hex(channelId);
	}

	Meta meta(byte[] channelId) throws DbException {
		synchronized (lockFor(channelId)) {
			return metaLocked(channelId);
		}
	}

	private Meta metaLocked(byte[] channelId) throws DbException {
		Meta m = storedMeta(channelId);
		if (m != null) {
			if (m.migrated >= 0L) return migrateLegacy(channelId, m);
			deleteLeftoverLegacy(channelId);
			return m;
		}
		return migrateLegacy(channelId, null);
	}

	@Nullable
	private Meta storedMeta(byte[] channelId) throws DbException {
		String encoded = settings.getSettings(metaNamespace(channelId))
				.get(META_KEY);
		if (encoded == null || encoded.isEmpty()) return null;
		try {
			return decodeMeta(decodeBase64(encoded));
		} catch (IOException | IllegalArgumentException e) {
			throw new DbException(e);
		}
	}

	private final Set<String> legacyChecked =
			ConcurrentHashMap.newKeySet();

	private void deleteLeftoverLegacy(byte[] channelId) throws DbException {
		String key = ChannelStore.hex(channelId);
		if (!legacyChecked.add(key)) return;
		String legacy = settings.getSetting(NS_LEGACY_POSTS, key);
		if (legacy != null) {
			settings.deleteSettings(NS_LEGACY_POSTS,
					Collections.singletonList(key));
		}
	}

	private Meta migrateLegacy(byte[] channelId, @Nullable Meta resumed)
			throws DbException {
		String key = ChannelStore.hex(channelId);
		String legacy = settings.getSetting(NS_LEGACY_POSTS, key);
		if (legacy == null || legacy.isEmpty()) {
			if (resumed == null) return new Meta();
			resumed.migrated = -1L;
			settings.mergeSettings(metaSettings(resumed),
					metaNamespace(channelId));
			return resumed;
		}
		Meta m = resumed == null ? new Meta() : resumed;
		long skip = resumed == null ? 0L : resumed.migrated;
		m.migrated = skip;
		try {
			InputStream in = java.util.Base64.getDecoder().wrap(
					new CharsInputStream(legacy));
			if (in.read() != ENCODED_LIST_START) throw new FormatException();
			BdfReader r = readerFactory.createReader(in);
			Map<String, Settings> batch = new LinkedHashMap<>();
			long batchChars = 0L;
			int batchPosts = 0;
			long index = 0L;
			while (true) {
				if (!r.hasDictionary()) {
					if (skipOther(r)) continue;
					break;
				}
				if (index < skip) {
					r.skipDictionary();
					index++;
					continue;
				}
				ChannelPost p = dictToPost(channelId, r.readDictionary(),
						true);
				index++;
				long seq = p.getSeqNum();
				if (seq >= 0L && seq <= ChannelConstants.MAX_SEQUENCE_NUMBER
						&& !m.holds(seq)) {
					Settings ps = postSettings(p);
					batch.put(postNamespace(channelId, seq), ps);
					batchChars += ps.get(POST_KEY).length();
					batchPosts++;
					account(m, p);
					if (p.isRead()) {
						m.readThrough = Math.max(m.readThrough, seq);
					}
					if (p.isWithheld()) m.withheld.add(seq);
					if (seq > m.tipSeq) {
						m.tipSeq = seq;
						m.tipHash = null;
					}
				}
				if (batchPosts >= MIGRATION_CHUNK_POSTS
						|| batchChars >= MIGRATION_CHUNK_CHARS) {
					m.migrated = index;
					batch.put(metaNamespace(channelId), metaSettings(m));
					settings.mergeSettings(batch);
					batch = new LinkedHashMap<>();
					batchChars = 0L;
					batchPosts = 0;
				}
			}
			m.migrated = -1L;
			batch.put(metaNamespace(channelId), metaSettings(m));
			settings.mergeSettings(batch);
		} catch (IOException | IllegalArgumentException e) {
			throw new DbException(e);
		}
		settings.deleteSettings(NS_LEGACY_POSTS,
				Collections.singletonList(key));
		return m;
	}

	private static boolean skipOther(BdfReader r) throws IOException {
		if (r.hasNull()) r.skipNull();
		else if (r.hasBoolean()) r.skipBoolean();
		else if (r.hasLong()) r.skipLong();
		else if (r.hasDouble()) r.skipDouble();
		else if (r.hasString()) r.skipString();
		else if (r.hasRaw()) r.skipRaw();
		else if (r.hasList()) r.skipList();
		else return false;
		return true;
	}

	private static final class CharsInputStream extends InputStream {

		private final String chars;
		private int pos;

		private CharsInputStream(String chars) {
			this.chars = chars;
		}

		@Override
		public int read() {
			return pos < chars.length() ? chars.charAt(pos++) & 0xFF : -1;
		}

		@Override
		public int read(byte[] b, int off, int len) {
			if (len == 0) return 0;
			if (pos >= chars.length()) return -1;
			int n = Math.min(len, chars.length() - pos);
			for (int i = 0; i < n; i++) {
				b[off + i] = (byte) chars.charAt(pos + i);
			}
			pos += n;
			return n;
		}
	}

	private static void account(Meta m, ChannelPost p) {
		m.add(p.getSeqNum());
		m.count++;
		long b = ChannelPostCeilings.storedBytes(p);
		m.bytes += b;
		if (p.signedByDelegate()) m.delegateBytes += b;
		if (p.getTtlMs() > 0) {
			m.minExpiry = Math.min(m.minExpiry,
					p.getTimestampHourMs() + p.getTtlMs());
		}
	}

	@Nullable
	ChannelChainTip tip(byte[] channelId) throws DbException {
		Meta m = meta(channelId);
		return m.tipSeq < 0 ? null : new ChannelChainTip(m.tipSeq, m.tipHash);
	}

	List<ChannelPost> getPosts(byte[] channelId) throws DbException {
		Meta m = meta(channelId);
		List<ChannelPost> out = new ArrayList<>((int) Math.min(m.count,
				100_000L));
		for (Long seq : m.heldSeqs()) {
			ChannelPost p = read(channelId, seq, m);
			if (p != null) out.add(p);
		}
		return out;
	}

	@Nullable
	ChannelPost getPost(byte[] channelId, long seq) throws DbException {
		Meta m = meta(channelId);
		if (!m.holds(seq)) return null;
		return read(channelId, seq, m);
	}

	List<ChannelPost> getPostsAfter(byte[] channelId, long since, int max)
			throws DbException {
		Meta m = meta(channelId);
		List<ChannelPost> out = new ArrayList<>();
		for (long[] r : m.held) {
			if (r[1] <= since) continue;
			for (long s = Math.max(r[0], since + 1L); ; s++) {
				if (out.size() >= max) return out;
				ChannelPost p = read(channelId, s, m);
				if (p != null) out.add(p);
				if (s >= r[1]) break;
			}
		}
		return out;
	}

	List<ChannelPost> getLatestPosts(byte[] channelId, int limit)
			throws DbException {
		Meta m = meta(channelId);
		List<ChannelPost> out = new ArrayList<>();
		for (int i = m.held.size() - 1; i >= 0 && out.size() < limit; i--) {
			long[] r = m.held.get(i);
			for (long s = r[1]; s >= r[0] && out.size() < limit; s--) {
				ChannelPost p = read(channelId, s, m);
				if (p != null) out.add(p);
			}
		}
		Collections.reverse(out);
		return out;
	}

	@Nullable
	private ChannelPost read(byte[] channelId, long seq, Meta m)
			throws DbException {
		String encoded = settings.getSettings(postNamespace(channelId, seq))
				.get(POST_KEY);
		if (encoded == null || encoded.isEmpty()) return null;
		try {
			ChannelPost p = dictToPost(channelId,
					bytesToDict(decodeBase64(encoded)), false);
			return p.withFlags(seq <= m.readThrough,
					m.withheld.contains(seq));
		} catch (IOException | IllegalArgumentException e) {
			throw new DbException(e);
		}
	}

	void append(byte[] channelId, ChannelPost post,
			@Nullable byte[] chainHash) throws DbException {
		append(channelId, post, chainHash,
				Collections.<String, Settings>emptyMap());
	}

	void append(byte[] channelId, ChannelPost post,
			@Nullable byte[] chainHash, Map<String, Settings> alsoWrite)
			throws DbException {
		synchronized (lockFor(channelId)) {
			Meta m = metaLocked(channelId);
			if (m.holds(post.getSeqNum())) {
				throw new DbException();
			}
			account(m, post);
			if (post.isRead()) {
				m.readThrough = Math.max(m.readThrough, post.getSeqNum());
			}
			if (post.isWithheld()) m.withheld.add(post.getSeqNum());
			if (post.getSeqNum() > m.tipSeq) {
				m.tipSeq = post.getSeqNum();
				m.tipHash = chainHash;
			}
			Map<String, Settings> batch = new LinkedHashMap<>(alsoWrite);
			batch.put(postNamespace(channelId, post.getSeqNum()),
					postSettings(post));
			batch.put(metaNamespace(channelId), metaSettings(m));
			settings.mergeSettings(batch);
		}
	}

	void replaceAll(byte[] channelId, List<ChannelPost> posts)
			throws DbException {
		synchronized (lockFor(channelId)) {
			Meta old = metaLocked(channelId);
			List<String> gone = new ArrayList<>();
			for (Long seq : old.heldSeqs()) {
				gone.add(postNamespace(channelId, seq));
			}
			settings.deleteNamespaces(gone);
			Meta m = new Meta();
			m.tipSeq = old.tipSeq;
			m.tipHash = old.tipHash;
			m.pruned = old.pruned;
			m.skipped = old.skipped;
			Map<String, Settings> batch = new LinkedHashMap<>();
			for (ChannelPost p : posts) {
				if (m.holds(p.getSeqNum())) continue;
				batch.put(postNamespace(channelId, p.getSeqNum()),
						postSettings(p));
				account(m, p);
				if (p.isRead()) {
					m.readThrough = Math.max(m.readThrough, p.getSeqNum());
				}
				if (p.isWithheld()) m.withheld.add(p.getSeqNum());
				if (p.getSeqNum() > m.tipSeq) {
					m.tipSeq = p.getSeqNum();
					m.tipHash = null;
				}
			}
			batch.put(metaNamespace(channelId), metaSettings(m));
			settings.mergeSettings(batch);
		}
	}

	List<ChannelPost> remove(byte[] channelId, Collection<Long> seqs,
			boolean pruned) throws DbException {
		synchronized (lockFor(channelId)) {
			Meta m = metaLocked(channelId);
			List<ChannelPost> removed = new ArrayList<>();
			List<String> gone = new ArrayList<>();
			for (Long seq : new TreeSet<>(seqs)) {
				if (!m.holds(seq)) continue;
				ChannelPost p = readQuietly(channelId, seq, m);
				gone.add(postNamespace(channelId, seq));
				m.remove(seq);
				m.count = Math.max(0L, m.count - 1L);
				if (p != null) {
					removed.add(p);
					long b = ChannelPostCeilings.storedBytes(p);
					m.bytes = Math.max(0L, m.bytes - b);
					if (p.signedByDelegate()) {
						m.delegateBytes = Math.max(0L, m.delegateBytes - b);
					}
					if (p.getTtlMs() > 0 && p.getTimestampHourMs()
							+ p.getTtlMs() <= m.minExpiry) {
						m.minExpiry = 0L;
					}
				}
				if (pruned) m.pruned++;
			}
			if (gone.isEmpty()) return removed;
			if (m.held.isEmpty()) {
				m.bytes = 0L;
				m.delegateBytes = 0L;
				m.minExpiry = Long.MAX_VALUE;
			}
			settings.deleteNamespaces(gone);
			settings.mergeSettings(metaSettings(m), metaNamespace(channelId));
			return removed;
		}
	}

	@Nullable
	private ChannelPost readQuietly(byte[] channelId, long seq, Meta m) {
		try {
			return read(channelId, seq, m);
		} catch (DbException e) {
			return null;
		}
	}

	void recordScan(byte[] channelId, long minExpiry, long nowMs)
			throws DbException {
		synchronized (lockFor(channelId)) {
			Meta m = metaLocked(channelId);
			m.minExpiry = minExpiry;
			m.lastScanMs = nowMs;
			settings.mergeSettings(metaSettings(m), metaNamespace(channelId));
		}
	}

	void setWithheld(byte[] channelId, Collection<Long> seqs)
			throws DbException {
		synchronized (lockFor(channelId)) {
			Meta m = metaLocked(channelId);
			boolean changed = false;
			for (Long seq : seqs) {
				if (m.holds(seq) && m.withheld.add(seq)) changed = true;
			}
			if (changed) {
				settings.mergeSettings(metaSettings(m),
						metaNamespace(channelId));
			}
		}
	}

	boolean markAllRead(byte[] channelId) throws DbException {
		synchronized (lockFor(channelId)) {
			Meta m = metaLocked(channelId);
			long high = m.highest();
			if (high <= m.readThrough) return false;
			m.readThrough = high;
			settings.mergeSettings(metaSettings(m), metaNamespace(channelId));
			return true;
		}
	}

	void passOver(byte[] channelId, long seq, @Nullable byte[] chainHash)
			throws DbException {
		synchronized (lockFor(channelId)) {
			Meta m = metaLocked(channelId);
			if (seq <= m.tipSeq) return;
			m.tipSeq = seq;
			m.tipHash = chainHash;
			m.skipped++;
			settings.mergeSettings(metaSettings(m), metaNamespace(channelId));
		}
	}

	void jumpTip(byte[] channelId, long seq) throws DbException {
		synchronized (lockFor(channelId)) {
			Meta m = metaLocked(channelId);
			if (seq <= m.tipSeq) return;
			m.tipSeq = seq;
			m.tipHash = null;
			settings.mergeSettings(metaSettings(m), metaNamespace(channelId));
		}
	}

	void removeAll(byte[] channelId) throws DbException {
		String key = ChannelStore.hex(channelId);
		synchronized (lockFor(channelId)) {
			Meta m;
			try {
				m = storedMeta(channelId);
			} catch (DbException e) {
				m = null;
			}
			List<String> gone = new ArrayList<>();
			if (m != null) {
				for (Long seq : m.heldSeqs()) {
					gone.add(postNamespace(channelId, seq));
				}
			}
			gone.add(metaNamespace(channelId));
			settings.deleteNamespaces(gone);
			if (settings.getSetting(NS_LEGACY_POSTS, key) != null) {
				settings.deleteSettings(NS_LEGACY_POSTS,
						Collections.singletonList(key));
			}
		}
		locks.remove(key);
		legacyChecked.remove(key);
	}

	private Settings postSettings(ChannelPost p) {
		Settings s = new Settings();
		s.put(POST_KEY, encodeBase64(dictToBytes(postToDict(p))));
		return s;
	}

	private Settings metaSettings(Meta m) {
		BdfDictionary d = new BdfDictionary();
		BdfList ranges = new BdfList();
		for (long[] r : m.held) {
			BdfList pair = new BdfList();
			pair.add(r[0]);
			pair.add(r[1]);
			ranges.add(pair);
		}
		d.put("held", ranges);
		d.put("tip", m.tipSeq);
		if (m.tipHash != null) d.put("tipHash", m.tipHash);
		d.put("count", m.count);
		d.put("bytes", m.bytes);
		d.put("read", m.readThrough);
		BdfList wh = new BdfList();
		for (Long seq : m.withheld) wh.add(seq);
		d.put("withheld", wh);
		d.put("minExpiry", m.minExpiry);
		d.put("pruned", m.pruned);
		d.put("skipped", m.skipped);
		d.put("lastScan", m.lastScanMs);
		d.put("dbytes", m.delegateBytes);
		if (m.migrated >= 0L) d.put("mig", m.migrated);
		Settings s = new Settings();
		s.put(META_KEY, encodeBase64(dictToBytes(d)));
		return s;
	}

	private Meta decodeMeta(byte[] bytes) throws IOException {
		BdfDictionary d = bytesToDict(bytes);
		Meta m = new Meta();
		for (Object o : d.getList("held", new BdfList())) {
			if (!(o instanceof BdfList)) continue;
			BdfList pair = (BdfList) o;
			if (pair.size() != 2) throw new FormatException();
			long from = pair.getLong(0);
			long to = pair.getLong(1);
			if (from < 0 || to < from
					|| to > ChannelConstants.MAX_SEQUENCE_NUMBER) {
				throw new FormatException();
			}
			m.held.add(new long[] {from, to});
		}
		m.tipSeq = d.getLong("tip", -1L);
		m.tipHash = d.getOptionalRaw("tipHash");
		m.count = d.getLong("count", 0L);
		m.bytes = d.getLong("bytes", 0L);
		m.readThrough = d.getLong("read", -1L);
		for (Object o : d.getList("withheld", new BdfList())) {
			if (o instanceof Long) m.withheld.add((Long) o);
		}
		m.minExpiry = d.getLong("minExpiry", Long.MAX_VALUE);
		m.pruned = d.getLong("pruned", 0L);
		m.skipped = d.getLong("skipped", 0L);
		m.lastScanMs = d.getLong("lastScan", 0L);
		m.delegateBytes = d.getLong("dbytes", 0L);
		m.migrated = d.getLong("mig", -1L);
		return m;
	}

	private BdfDictionary postToDict(ChannelPost p) {
		BdfDictionary d = new BdfDictionary();
		d.put("seqNum", p.getSeqNum());
		d.put("prevHash", p.getPrevHash());
		d.put("timestampHourMs", p.getTimestampHourMs());
		d.put("body", p.getBody());
		d.put("ttlMs", p.getTtlMs());
		d.put("signature", p.getSignature());
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
		if (p.getFormatVersion() != ChannelPost.FORMAT_LEGACY) {
			d.put("pv", (long) p.getFormatVersion());
		}
		if (p.getSalt() != null) d.put("salt", p.getSalt());
		return d;
	}

	private ChannelPost dictToPost(byte[] channelId, BdfDictionary d,
			boolean legacy) throws FormatException {
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
		long version = d.getLong("pv", (long) ChannelPost.FORMAT_LEGACY);
		return new ChannelPost(channelId,
				d.getLong("seqNum"),
				d.getRaw("prevHash"),
				d.getLong("timestampHourMs"),
				d.getString("body"),
				atts,
				d.getLong("ttlMs"),
				d.getRaw("signature"),
				legacy && d.getBoolean("read", false),
				d.getOptionalRaw("delegateSignerEd25519"),
				d.getOptionalRaw("delegateSignerMlDsa"),
				legacy && d.getBoolean("withheld", false),
				(int) version, d.getOptionalRaw("salt"));
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

	private BdfDictionary bytesToDict(byte[] bytes) throws IOException {
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
