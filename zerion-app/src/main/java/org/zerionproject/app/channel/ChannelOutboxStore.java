package org.zerionproject.app.channel;

import org.zerionproject.app.api.channel.ChannelQueuedItem;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class ChannelOutboxStore {

	static final String NS_PREFIX = "zerion-channels-outbox:";
	static final int MAX_ENTRIES = 50;
	static final long MAX_WAIT_MS = 7L * 24L * 60L * 60L * 1000L;
	static final long KEEP_NOT_SENT_MS = MAX_WAIT_MS;
	static final int MAX_TRIES = 6;
	static final long RETRY_BASE_MS = 60L * 1000L;
	static final long RETRY_MAX_MS = 30L * 60L * 1000L;
	static final long MAX_DEFER_MS = 60L * 60L * 1000L;

	private static final String KEY = "q";
	private static final int FORMAT_1 = 1;
	private static final int FORMAT_2 = 2;
	private static final int FORMAT = 3;
	private static final int MAX_TEXT_CHARS = 8192;

	private final SettingsManager settings;
	private final Map<String, List<Entry>> held = new HashMap<>();

	ChannelOutboxStore(SettingsManager settings) {
		this.settings = settings;
	}

	static final class Entry {

		final long id;
		final ChannelQueuedItem.Kind kind;
		final long postSeq;
		final String text;
		final long ttlSeconds;
		final boolean commentsOn;
		final long afterSeq;
		final long queuedAtMs;
		final long notSentAtMs;
		final boolean tried;
		final int failures;
		final long nextTryAtMs;
		final ChannelQueuedItem.Reason reason;
		final long targetExpiresAtMs;
		final String accessMark;

		Entry(long id, ChannelQueuedItem.Kind kind, long postSeq,
				String text, long ttlSeconds, boolean commentsOn,
				long afterSeq, long queuedAtMs, long notSentAtMs,
				boolean tried) {
			this(id, kind, postSeq, text, ttlSeconds, commentsOn, afterSeq,
					queuedAtMs, notSentAtMs, tried, 0, 0L,
					notSentAtMs > 0L ? ChannelQueuedItem.Reason.REFUSED
							: ChannelQueuedItem.Reason.NONE, 0L, "");
		}

		private Entry(long id, ChannelQueuedItem.Kind kind, long postSeq,
				String text, long ttlSeconds, boolean commentsOn,
				long afterSeq, long queuedAtMs, long notSentAtMs,
				boolean tried, int failures, long nextTryAtMs,
				ChannelQueuedItem.Reason reason, long targetExpiresAtMs,
				String accessMark) {
			this.id = id;
			this.kind = kind;
			this.postSeq = postSeq;
			this.text = text;
			this.ttlSeconds = ttlSeconds;
			this.commentsOn = commentsOn;
			this.afterSeq = afterSeq;
			this.queuedAtMs = queuedAtMs;
			this.notSentAtMs = notSentAtMs;
			this.tried = tried;
			this.failures = failures;
			this.nextTryAtMs = nextTryAtMs;
			this.reason = reason;
			this.targetExpiresAtMs = targetExpiresAtMs;
			this.accessMark = accessMark;
		}

		boolean notSent() {
			return notSentAtMs > 0L;
		}

		boolean deniedAccess() {
			return notSent() && reason == ChannelQueuedItem.Reason.NO_ACCESS;
		}

		Entry markedNotSent(long now, ChannelQueuedItem.Reason why) {
			return markedNotSent(now, why, accessMark);
		}

		Entry markedNotSent(long now, ChannelQueuedItem.Reason why,
				String mark) {
			return new Entry(id, kind, postSeq, text, ttlSeconds,
					commentsOn, afterSeq, queuedAtMs, Math.max(1L, now),
					tried, failures, 0L, why, targetExpiresAtMs, mark);
		}

		Entry waitingFrom(long now) {
			return new Entry(id, kind, postSeq, text, ttlSeconds,
					commentsOn, afterSeq, now, 0L, tried, 0, 0L,
					ChannelQueuedItem.Reason.NONE, targetExpiresAtMs, "");
		}

		Entry waitingAgain() {
			return new Entry(id, kind, postSeq, text, ttlSeconds,
					commentsOn, afterSeq, queuedAtMs, 0L, tried, 0, 0L,
					ChannelQueuedItem.Reason.NONE, targetExpiresAtMs, "");
		}

		Entry markedTried() {
			return new Entry(id, kind, postSeq, text, ttlSeconds,
					commentsOn, afterSeq, queuedAtMs, notSentAtMs, true,
					failures, nextTryAtMs, reason, targetExpiresAtMs,
					accessMark);
		}

		Entry failedAt(long now, boolean wasTried) {
			int n = failures + 1;
			long delay = Math.min(RETRY_MAX_MS,
					RETRY_BASE_MS << Math.min(20, n - 1));
			return new Entry(id, kind, postSeq, text, ttlSeconds,
					commentsOn, afterSeq, queuedAtMs, notSentAtMs,
					tried || wasTried, n, now + delay, reason,
					targetExpiresAtMs, accessMark);
		}

		Entry deferredUntil(long at) {
			return new Entry(id, kind, postSeq, text, ttlSeconds,
					commentsOn, afterSeq, queuedAtMs, notSentAtMs, tried,
					failures, at, reason, targetExpiresAtMs, accessMark);
		}

		Entry withAfterSeq(long seq) {
			return new Entry(id, kind, postSeq, text, ttlSeconds,
					commentsOn, seq, queuedAtMs, notSentAtMs, tried,
					failures, nextTryAtMs, reason, targetExpiresAtMs,
					accessMark);
		}

		Entry withTargetExpiry(long at) {
			return new Entry(id, kind, postSeq, text, ttlSeconds,
					commentsOn, afterSeq, queuedAtMs, notSentAtMs, tried,
					failures, nextTryAtMs, reason, at, accessMark);
		}

		ChannelQueuedItem toItem() {
			return new ChannelQueuedItem(id, kind, postSeq, text,
					commentsOn, queuedAtMs, notSent(),
					notSent() ? reason : ChannelQueuedItem.Reason.NONE,
					ttlSeconds);
		}
	}

	private interface Change {
		@Nullable
		Entry apply(Entry e);
	}

	synchronized List<Entry> list(byte[] channelId) throws DbException {
		return new ArrayList<>(load(channelId));
	}

	@Nullable
	synchronized Entry get(byte[] channelId, long id) throws DbException {
		for (Entry e : load(channelId)) {
			if (e.id == id) return e;
		}
		return null;
	}

	synchronized boolean hasWaiting(byte[] channelId) throws DbException {
		for (Entry e : load(channelId)) {
			if (!e.notSent()) return true;
		}
		return false;
	}

	synchronized boolean hasDeniedAccess(byte[] channelId)
			throws DbException {
		for (Entry e : load(channelId)) {
			if (e.deniedAccess()) return true;
		}
		return false;
	}

	synchronized boolean holds(byte[] channelId, long id)
			throws DbException {
		return get(channelId, id) != null;
	}

	synchronized boolean add(byte[] channelId, Entry entry, long now)
			throws DbException {
		List<Entry> current = load(channelId);
		List<Entry> next = aged(current, now);
		if (next.size() >= MAX_ENTRIES) {
			if (!sameEntries(current, next)) write(channelId, next);
			return false;
		}
		next.add(entry);
		write(channelId, next);
		return true;
	}

	synchronized boolean takeOver(byte[] channelId, Entry entry)
			throws DbException {
		List<Entry> current = load(channelId);
		List<Entry> next = new ArrayList<>(current.size());
		boolean took = false;
		for (Entry e : current) {
			if (e.kind != entry.kind || e.postSeq != entry.postSeq) {
				next.add(e);
			} else if (!took && !e.notSent()) {
				next.add(entry);
				took = true;
			}
		}
		if (took) write(channelId, next);
		return took;
	}

	synchronized boolean remove(byte[] channelId, long id)
			throws DbException {
		List<Entry> current = load(channelId);
		List<Entry> next = new ArrayList<>(current.size());
		for (Entry e : current) {
			if (e.id != id) next.add(e);
		}
		if (next.size() == current.size()) return false;
		write(channelId, next);
		return true;
	}

	synchronized boolean removeLike(byte[] channelId,
			ChannelQueuedItem.Kind kind, long postSeq) throws DbException {
		List<Entry> current = load(channelId);
		List<Entry> next = new ArrayList<>(current.size());
		for (Entry e : current) {
			if (e.kind != kind || e.postSeq != postSeq) next.add(e);
		}
		if (next.size() == current.size()) return false;
		write(channelId, next);
		return true;
	}

	synchronized boolean markNotSent(byte[] channelId, long id, long now,
			ChannelQueuedItem.Reason why) throws DbException {
		return change(channelId, id,
				e -> e.notSent() ? null : e.markedNotSent(now, why));
	}

	synchronized boolean markWaitingNotSent(byte[] channelId, long now,
			ChannelQueuedItem.Reason why, String mark, Set<Long> except)
			throws DbException {
		List<Entry> current = load(channelId);
		List<Entry> next = new ArrayList<>(current.size());
		boolean changed = false;
		for (Entry e : current) {
			if (!e.notSent() && !except.contains(e.id)) {
				next.add(e.markedNotSent(now, why, mark));
				changed = true;
			} else {
				next.add(e);
			}
		}
		if (changed) write(channelId, next);
		return changed;
	}

	synchronized boolean reviveAccess(byte[] channelId, String mark)
			throws DbException {
		List<Entry> current = load(channelId);
		List<Entry> next = new ArrayList<>(current.size());
		boolean stamped = false;
		boolean revived = false;
		for (Entry e : current) {
			if (!e.deniedAccess() || e.accessMark.equals(mark)) {
				next.add(e);
			} else if (e.accessMark.isEmpty()) {
				next.add(e.markedNotSent(e.notSentAtMs, e.reason, mark));
				stamped = true;
			} else {
				next.add(e.waitingAgain());
				revived = true;
			}
		}
		if (stamped || revived) write(channelId, next);
		return revived;
	}

	synchronized void markTried(byte[] channelId, long id)
			throws DbException {
		change(channelId, id, e -> e.tried ? null : e.markedTried());
	}

	synchronized boolean fail(byte[] channelId, long id, long now,
			boolean wasTried) throws DbException {
		boolean[] gaveUp = new boolean[1];
		change(channelId, id, e -> {
			if (e.notSent()) return null;
			Entry next = e.failedAt(now, wasTried);
			if (next.failures < MAX_TRIES) return next;
			gaveUp[0] = true;
			return next.markedNotSent(now,
					ChannelQueuedItem.Reason.TOO_MANY_TRIES);
		});
		return gaveUp[0];
	}

	synchronized void defer(byte[] channelId, long id, long at)
			throws DbException {
		change(channelId, id, e -> e.nextTryAtMs == at
				? null : e.deferredUntil(at));
	}

	synchronized void raiseAfterSeq(byte[] channelId, long id, long seq)
			throws DbException {
		List<Entry> current = load(channelId);
		List<Entry> next = new ArrayList<>(current.size());
		boolean after = false;
		boolean changed = false;
		for (Entry e : current) {
			if (after && e.kind == ChannelQueuedItem.Kind.POST
					&& e.afterSeq < seq) {
				next.add(e.withAfterSeq(seq));
				changed = true;
			} else {
				next.add(e);
			}
			if (e.id == id) after = true;
		}
		if (changed) write(channelId, next);
	}

	synchronized boolean retry(byte[] channelId, long id, long now)
			throws DbException {
		List<Entry> current = load(channelId);
		List<Entry> next = new ArrayList<>(current.size());
		Entry again = null;
		for (Entry e : current) {
			if (e.id == id && e.notSent()) again = e.waitingFrom(now);
			else next.add(e);
		}
		if (again == null) return false;
		next.add(again);
		write(channelId, next);
		return true;
	}

	synchronized boolean age(byte[] channelId, long now)
			throws DbException {
		List<Entry> current = load(channelId);
		List<Entry> next = aged(current, now);
		if (sameEntries(current, next)) return false;
		write(channelId, next);
		return true;
	}

	synchronized void removeAll(byte[] channelId) throws DbException {
		held.remove(ChannelStore.hex(channelId));
		settings.deleteNamespaces(Collections.singletonList(
				namespace(channelId)));
	}

	static String namespace(byte[] channelId) {
		return NS_PREFIX + ChannelStore.hex(channelId);
	}

	private boolean change(byte[] channelId, long id, Change change)
			throws DbException {
		List<Entry> current = load(channelId);
		List<Entry> next = new ArrayList<>(current.size());
		boolean changed = false;
		for (Entry e : current) {
			Entry replaced = e.id == id ? change.apply(e) : null;
			if (replaced != null) {
				next.add(replaced);
				changed = true;
			} else {
				next.add(e);
			}
		}
		if (changed) write(channelId, next);
		return changed;
	}

	private static List<Entry> aged(List<Entry> entries, long now) {
		List<Entry> out = new ArrayList<>(entries.size());
		for (Entry e : entries) {
			if (e.notSent()) {
				if (now - e.notSentAtMs < KEEP_NOT_SENT_MS) out.add(e);
			} else if (now - e.queuedAtMs >= MAX_WAIT_MS) {
				out.add(e.markedNotSent(now,
						ChannelQueuedItem.Reason.EXPIRED));
			} else {
				out.add(e);
			}
		}
		return out;
	}

	private static boolean sameEntries(List<Entry> a, List<Entry> b) {
		if (a.size() != b.size()) return false;
		for (int i = 0; i < a.size(); i++) {
			if (a.get(i).id != b.get(i).id
					|| a.get(i).notSentAtMs != b.get(i).notSentAtMs) {
				return false;
			}
		}
		return true;
	}

	private List<Entry> load(byte[] channelId) throws DbException {
		String key = ChannelStore.hex(channelId);
		List<Entry> cached = held.get(key);
		if (cached != null) return cached;
		String encoded = settings.getSettings(namespace(channelId)).get(KEY);
		List<Entry> read = encoded == null || encoded.isEmpty()
				? new ArrayList<>() : decode(encoded);
		List<Entry> fixed = Collections.unmodifiableList(read);
		held.put(key, fixed);
		return fixed;
	}

	private void write(byte[] channelId, List<Entry> entries)
			throws DbException {
		String key = ChannelStore.hex(channelId);
		held.remove(key);
		if (entries.isEmpty()) {
			settings.deleteNamespaces(Collections.singletonList(
					namespace(channelId)));
		} else {
			Settings out = new Settings();
			out.put(KEY, encode(entries));
			settings.mergeSettings(out, namespace(channelId));
		}
		held.put(key, Collections.unmodifiableList(
				new ArrayList<>(entries)));
	}

	private static String encode(List<Entry> entries) throws DbException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			DataOutputStream out = new DataOutputStream(bytes);
			out.writeByte(FORMAT);
			out.writeInt(entries.size());
			for (Entry e : entries) {
				out.writeLong(e.id);
				out.writeUTF(e.kind.name());
				out.writeLong(e.postSeq);
				out.writeUTF(e.text);
				out.writeLong(e.ttlSeconds);
				out.writeBoolean(e.commentsOn);
				out.writeLong(e.afterSeq);
				out.writeLong(e.queuedAtMs);
				out.writeLong(e.notSentAtMs);
				out.writeBoolean(e.tried);
				out.writeInt(e.failures);
				out.writeLong(e.nextTryAtMs);
				out.writeUTF(e.reason.name());
				out.writeLong(e.targetExpiresAtMs);
				out.writeUTF(e.accessMark);
			}
			out.flush();
		} catch (IOException e) {
			throw new DbException(e);
		}
		return java.util.Base64.getEncoder().withoutPadding()
				.encodeToString(bytes.toByteArray());
	}

	private static List<Entry> decode(String encoded) {
		List<Entry> out = new ArrayList<>();
		try {
			DataInputStream in = new DataInputStream(new ByteArrayInputStream(
					java.util.Base64.getDecoder().decode(encoded)));
			int format = in.readByte();
			if (format != FORMAT && format != FORMAT_2
					&& format != FORMAT_1) {
				return out;
			}
			int n = in.readInt();
			if (n < 0 || n > MAX_ENTRIES) return out;
			for (int i = 0; i < n; i++) {
				long id = in.readLong();
				ChannelQueuedItem.Kind kind = kindOf(in.readUTF());
				long postSeq = in.readLong();
				String text = in.readUTF();
				long ttl = in.readLong();
				boolean commentsOn = in.readBoolean();
				long afterSeq = in.readLong();
				long queuedAt = in.readLong();
				long notSentAt = in.readLong();
				boolean tried = in.readBoolean();
				int failures = 0;
				long nextTryAt = 0L;
				ChannelQueuedItem.Reason reason = notSentAt > 0L
						? ChannelQueuedItem.Reason.REFUSED
						: ChannelQueuedItem.Reason.NONE;
				long targetExpiresAt = 0L;
				String mark = "";
				if (format >= FORMAT_2) {
					failures = in.readInt();
					nextTryAt = in.readLong();
					ChannelQueuedItem.Reason r = reasonOf(in.readUTF());
					if (r != null) reason = r;
				}
				if (format >= FORMAT) {
					targetExpiresAt = in.readLong();
					mark = in.readUTF();
				}
				if (kind == null || text.length() > MAX_TEXT_CHARS
						|| failures < 0) {
					continue;
				}
				out.add(new Entry(id, kind, postSeq, text, ttl, commentsOn,
						afterSeq, queuedAt, notSentAt, tried, failures,
						nextTryAt, reason, targetExpiresAt, mark));
			}
		} catch (IOException | IllegalArgumentException e) {
			return new ArrayList<>();
		}
		return out;
	}

	@Nullable
	private static ChannelQueuedItem.Kind kindOf(String name) {
		try {
			return ChannelQueuedItem.Kind.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	@Nullable
	private static ChannelQueuedItem.Reason reasonOf(String name) {
		try {
			return ChannelQueuedItem.Reason.valueOf(name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}
}
