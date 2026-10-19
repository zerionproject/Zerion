package com.professor.zerion.android.message;

import android.content.Context;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import androidx.annotation.PluralsRes;
import androidx.annotation.StringRes;

@ThreadSafe
@NotNullByDefault
public final class PendingSends {

	static final long RESOLVED_TTL_MS = 5L * 60L * 1000L;
	static final long MAX_KEPT_BYTES = 24L * 1024L * 1024L;
	static final int MAX_REMOVAL_EVENTS = 64;

	private static final PendingSends INSTANCE = new PendingSends();

	public static PendingSends get() {
		return INSTANCE;
	}

	public static String groupScope(byte[] groupId) {
		return "grouptr:" + StringUtils.toHexString(groupId);
	}

	public static String channelPostScope(byte[] channelId) {
		return "channel-post:" + StringUtils.toHexString(channelId);
	}

	public static String channelCommentScope(byte[] channelId,
			long parentSeq) {
		return channelCommentPrefix(channelId) + parentSeq;
	}

	public static String channelQueueScope(byte[] channelId) {
		return "channel:" + StringUtils.toHexString(channelId);
	}

	private static String channelCommentPrefix(byte[] channelId) {
		return "channel-comment:" + StringUtils.toHexString(channelId) + ":";
	}

	public interface Attempt {
		Result run();
	}

	public interface Listener {
		void onPendingChanged(Change change);
	}

	public interface Shown {
		boolean contains(String identity);
	}

	public enum Outcome {
		STORED,
		HANDED_OVER,
		FAILED,
		RETURNED
	}

	public enum Kind {
		ADDED,
		STARTED,
		STORED,
		HANDED_OVER,
		FAILED,
		RETURNED,
		REMOVED,
		QUEUED_RETRY,
		CLEARED
	}

	enum Status {
		SENDING,
		NOT_SENT,
		RESOLVED,
		RETURNED
	}

	@NotNullByDefault
	public static final class Notice {

		@StringRes
		private final int string;
		@PluralsRes
		private final int plural;
		private final Object[] args;
		private final int count;
		@Nullable
		private final Notice next;

		private Notice(@StringRes int string, @PluralsRes int plural,
				int count, @Nullable Notice next, Object... args) {
			this.string = string;
			this.plural = plural;
			this.count = count;
			this.next = next;
			this.args = args;
		}

		public static Notice of(@StringRes int string) {
			return new Notice(string, 0, 0, null);
		}

		public static Notice plural(@PluralsRes int plural, int count,
				Object... args) {
			return new Notice(0, plural, count, null, args);
		}

		public Notice then(Notice more) {
			Notice tail = next == null ? more : next.then(more);
			return new Notice(string, plural, count, tail, args);
		}

		@PluralsRes
		int getPlural() {
			return plural;
		}

		int getCount() {
			return count;
		}

		public CharSequence text(Context ctx) {
			CharSequence own;
			if (plural != 0) {
				Object[] shown = args.length == 0
						? new Object[] {count} : args;
				own = ctx.getResources().getQuantityString(plural, count,
						shown);
			} else {
				own = ctx.getText(string);
			}
			return next == null ? own : ctx.getString(
					R.string.message_state_notice_join, own, next.text(ctx));
		}
	}

	@NotNullByDefault
	public static final class Result {

		private final Outcome outcome;
		@Nullable
		private final String identity;
		@Nullable
		private final Notice notice;

		private Result(Outcome outcome, @Nullable String identity,
				@Nullable Notice notice) {
			this.outcome = outcome;
			this.identity = identity;
			this.notice = notice;
		}

		public static Result stored(@Nullable String identity,
				@Nullable Notice notice) {
			return new Result(Outcome.STORED, identity, notice);
		}

		public static Result handedOver(@Nullable String identity,
				@Nullable Notice notice) {
			return new Result(Outcome.HANDED_OVER, identity, notice);
		}

		public static Result failed(@Nullable Notice why) {
			return new Result(Outcome.FAILED, null, why);
		}

		public static Result refused(Notice why) {
			return new Result(Outcome.RETURNED, null, why);
		}

		public Outcome getOutcome() {
			return outcome;
		}
	}

	@NotNullByDefault
	public static final class Change {

		private final String scope;
		private final Kind kind;
		@Nullable
		private final Entry entry;
		@Nullable
		private final Notice notice;

		Change(String scope, Kind kind, @Nullable Entry entry,
				@Nullable Notice notice) {
			this.scope = scope;
			this.kind = kind;
			this.entry = entry;
			this.notice = notice;
		}

		public String getScope() {
			return scope;
		}

		public Kind getKind() {
			return kind;
		}

		@Nullable
		public Entry getEntry() {
			return entry;
		}

		@Nullable
		public Notice getNotice() {
			return notice;
		}
	}

	@NotNullByDefault
	public static final class Entry {

		private final long id;
		private final String scope;
		private final String text;
		private final long weight;
		private final boolean editable;
		private final long createdAtMs;
		private final Attempt attempt;
		@Nullable
		private volatile Object payload;
		private volatile Status status;
		private volatile boolean running = false;
		private volatile boolean handedOver = false;
		private volatile boolean dropWhenDone = false;
		private volatile boolean scopeRemoved = false;
		private volatile long discardedAt = -1L;
		private volatile long discardedRemovals = -1L;
		private volatile long session = 0L;
		private volatile long resolvedAtMs = 0L;
		@Nullable
		private volatile String identity;
		@Nullable
		private volatile Notice untold;

		Entry(long id, String scope, String text, @Nullable Object payload,
				long weight, boolean editable, long createdAtMs,
				Attempt attempt, Status status) {
			this.id = id;
			this.scope = scope;
			this.text = text;
			this.payload = payload;
			this.weight = weight;
			this.editable = editable;
			this.createdAtMs = createdAtMs;
			this.attempt = attempt;
			this.status = status;
		}

		public long getId() {
			return id;
		}

		public String getScope() {
			return scope;
		}

		public String getText() {
			return text;
		}

		@Nullable
		public Object getPayload() {
			return payload;
		}

		public long getCreatedAtMs() {
			return createdAtMs;
		}

		public MessageState getState() {
			Status s = status;
			if (s == Status.NOT_SENT || s == Status.RETURNED) {
				return MessageState.NOT_SENT;
			}
			if (s == Status.RESOLVED) {
				return handedOver ? MessageState.WAITING : MessageState.SENT;
			}
			return MessageState.SENDING;
		}

		public boolean canRetry() {
			return status == Status.NOT_SENT && !running;
		}

		public boolean canDiscard() {
			Status s = status;
			return (s == Status.NOT_SENT || s == Status.RETURNED) && !running;
		}

		public boolean canEdit() {
			return editable && canDiscard();
		}

		public boolean isReturned() {
			return status == Status.RETURNED;
		}

		@Nullable
		public String getIdentity() {
			return identity;
		}

		Status getStatus() {
			return status;
		}
	}

	private static final class Registration {

		final Listener listener;
		final Set<String> scopes;

		Registration(Listener listener, Set<String> scopes) {
			this.listener = listener;
			this.scopes = scopes;
		}
	}

	public interface ClearListener {
		void onCleared(String[] prefixes);
	}

	public static boolean affects(String[] prefixes, String scope) {
		return prefixes.length == 0 || startsWithAny(scope, prefixes);
	}

	private final Object lock = new Object();
	private final List<Entry> entries = new ArrayList<>();
	private final Map<String, List<Notice>> notices = new LinkedHashMap<>();
	private final Map<String, Integer> removedOnLock = new LinkedHashMap<>();
	private final Map<String, Integer> removedForSpace =
			new LinkedHashMap<>();
	private final Set<String> queuedRetries = new HashSet<>();
	private final List<Registration> listeners = new CopyOnWriteArrayList<>();
	private final List<ClearListener> clearListeners =
			new CopyOnWriteArrayList<>();
	private final AtomicLong ids = new AtomicLong();
	private long clears = 0L;
	private long session = 0L;
	private final List<String[]> removalEvents = new ArrayList<>();
	private long removalBase = 0L;
	LongSupplier clock = System::currentTimeMillis;

	PendingSends() {
	}

	public void addListener(Listener l, String... scopes) {
		listeners.add(new Registration(l,
				new HashSet<>(Arrays.asList(scopes))));
	}

	public void removeListener(Listener l) {
		for (Registration r : listeners) {
			if (r.listener == l) listeners.remove(r);
		}
	}

	public void addClearListener(ClearListener l) {
		clearListeners.add(l);
	}

	public void removeClearListener(ClearListener l) {
		clearListeners.remove(l);
	}

	private boolean heard(String scope) {
		for (Registration r : listeners) {
			if (r.scopes.contains(scope)) return true;
		}
		return false;
	}

	public Entry add(String scope, String text, @Nullable Object payload,
			long weight, boolean editable, Attempt attempt) {
		return insert(scope, text, payload, weight, editable, attempt,
				Status.SENDING);
	}

	public Entry addNotSent(String scope, String text,
			@Nullable Object payload, long weight, boolean editable,
			Attempt attempt) {
		return insert(scope, text, payload, weight, editable, attempt,
				Status.NOT_SENT);
	}

	private Entry insert(String scope, String text, @Nullable Object payload,
			long weight, boolean editable, Attempt attempt, Status status) {
		Entry e = new Entry(ids.incrementAndGet(), scope, text, payload,
				weight, editable, clock.getAsLong(), attempt, status);
		Set<String> trimmed;
		synchronized (lock) {
			e.session = session;
			entries.add(e);
			trimmed = keepWithinLimit();
		}
		notifyListeners(new Change(scope, Kind.ADDED, e, null));
		notifyRemoved(trimmed);
		return e;
	}

	public boolean restore(Entry e) {
		synchronized (lock) {
			if (entries.contains(e) || !e.canDiscard()
					|| e.discardedAt != clears
					|| removedSince(e)) {
				return false;
			}
			int at = 0;
			while (at < entries.size() && entries.get(at).id < e.id) at++;
			entries.add(at, e);
		}
		notifyListeners(new Change(e.scope, Kind.ADDED, e, null));
		return true;
	}

	public boolean start(Entry e, Executor io) {
		synchronized (lock) {
			if (!entries.contains(e) || e.status != Status.SENDING
					|| e.running) {
				return false;
			}
			e.running = true;
		}
		execute(e, io);
		return true;
	}

	public boolean retry(Entry e, Executor io) {
		synchronized (lock) {
			if (!entries.contains(e) || e.status != Status.NOT_SENT
					|| e.running) {
				return false;
			}
			e.status = Status.SENDING;
			e.untold = null;
			e.running = true;
		}
		notifyListeners(new Change(e.scope, Kind.STARTED, e, null));
		execute(e, io);
		return true;
	}

	public boolean discard(Entry e) {
		synchronized (lock) {
			if (!e.canDiscard() || !entries.remove(e)) return false;
			e.discardedAt = clears;
			e.discardedRemovals = removalBase + removalEvents.size();
			Notice untold = e.untold;
			if (untold != null) {
				e.untold = null;
				hold(e.scope, untold);
			}
		}
		notifyListeners(new Change(e.scope, Kind.REMOVED, e, null));
		return true;
	}

	public List<Entry> entries(String scope) {
		List<Entry> out = new ArrayList<>();
		synchronized (lock) {
			dropExpired();
			for (Entry e : entries) {
				if (e.scope.equals(scope) && !e.dropWhenDone) out.add(e);
			}
		}
		return out;
	}

	public List<Entry> retryable(String scope) {
		List<Entry> out = new ArrayList<>();
		for (Entry e : entries(scope)) {
			if (e.canRetry()) out.add(e);
		}
		return out;
	}

	public int settle(String scope, Shown shown) {
		int removed = 0;
		synchronized (lock) {
			dropExpired();
			Iterator<Entry> it = entries.iterator();
			while (it.hasNext()) {
				Entry e = it.next();
				String identity = e.identity;
				if (e.scope.equals(scope) && e.status == Status.RESOLVED
						&& identity != null && shown.contains(identity)) {
					it.remove();
					removed++;
				}
			}
		}
		return removed;
	}

	public List<Notice> takeNotices(String scope) {
		List<Notice> out = new ArrayList<>();
		synchronized (lock) {
			List<Notice> held = notices.remove(scope);
			if (held != null) out.addAll(held);
			for (Entry e : entries) {
				Notice n = e.untold;
				if (n != null && e.scope.equals(scope)) {
					out.add(n);
					e.untold = null;
				}
			}
			Integer locked = removedOnLock.remove(scope);
			if (locked != null) {
				out.add(Notice.plural(R.plurals.message_state_removed_on_lock,
						locked));
			}
			Integer space = removedForSpace.remove(scope);
			if (space != null) {
				out.add(Notice.plural(
						R.plurals.message_state_removed_for_space, space));
			}
		}
		return out;
	}

	public static List<Entry> visible(List<Entry> entries, Shown shown) {
		List<Entry> out = new ArrayList<>();
		for (Entry e : entries) {
			Status status = e.status;
			String identity = e.identity;
			if (status == Status.RESOLVED && identity != null
					&& shown.contains(identity)) {
				continue;
			}
			out.add(e);
		}
		return out;
	}

	public boolean beginQueuedRetry(String scope, long itemId) {
		synchronized (lock) {
			if (!queuedRetries.add(scope + "#" + itemId)) return false;
		}
		notifyListeners(new Change(scope, Kind.QUEUED_RETRY, null, null));
		return true;
	}

	public void endQueuedRetry(String scope, long itemId) {
		synchronized (lock) {
			queuedRetries.remove(scope + "#" + itemId);
		}
		notifyListeners(new Change(scope, Kind.QUEUED_RETRY, null, null));
	}

	public boolean isQueuedRetry(String scope, long itemId) {
		synchronized (lock) {
			return queuedRetries.contains(scope + "#" + itemId);
		}
	}

	public void clear() {
		Set<String> scopes = new HashSet<>();
		synchronized (lock) {
			clears++;
			Iterator<Entry> it = entries.iterator();
			while (it.hasNext()) {
				Entry e = it.next();
				scopes.add(e.scope);
				if (!e.running && e.status != Status.RESOLVED) {
					count(removedOnLock, e.scope);
				}
				dropOrDefer(e, it);
			}
		}
		for (String scope : scopes) {
			notifyListeners(new Change(scope, Kind.CLEARED, null, null));
		}
		for (ClearListener l : clearListeners) l.onCleared(new String[0]);
	}

	public void clearSilently() {
		Set<String> scopes = new HashSet<>();
		synchronized (lock) {
			clears++;
			session++;
			Iterator<Entry> it = entries.iterator();
			while (it.hasNext()) {
				Entry e = it.next();
				scopes.add(e.scope);
				dropOrDefer(e, it);
			}
			notices.clear();
			removedOnLock.clear();
			removedForSpace.clear();
		}
		for (String scope : scopes) {
			notifyListeners(new Change(scope, Kind.CLEARED, null, null));
		}
		for (ClearListener l : clearListeners) l.onCleared(new String[0]);
	}

	public int takeRemovedOnLock() {
		synchronized (lock) {
			int total = 0;
			for (int n : removedOnLock.values()) total += n;
			removedOnLock.clear();
			return total;
		}
	}

	private boolean removedSince(Entry e) {
		if (e.discardedRemovals < removalBase) return true;
		for (int i = (int) (e.discardedRemovals - removalBase);
				i < removalEvents.size(); i++) {
			if (startsWithAny(e.scope, removalEvents.get(i))) return true;
		}
		return false;
	}

	public void removeGroup(byte[] groupId) {
		removeScopes(groupScope(groupId));
	}

	public void removeChannel(byte[] channelId) {
		removeScopes(channelPostScope(channelId),
				channelCommentPrefix(channelId));
	}

	private void removeScopes(String... prefixes) {
		Set<String> scopes = new HashSet<>();
		synchronized (lock) {
			Iterator<Entry> it = entries.iterator();
			while (it.hasNext()) {
				Entry e = it.next();
				if (!startsWithAny(e.scope, prefixes)) continue;
				scopes.add(e.scope);
				e.scopeRemoved = true;
				dropOrDefer(e, it);
			}
			for (Map<String, ?> m : Arrays.asList(notices, removedOnLock,
					removedForSpace)) {
				Iterator<String> n = m.keySet().iterator();
				while (n.hasNext()) {
					if (startsWithAny(n.next(), prefixes)) n.remove();
				}
			}
			removalEvents.add(prefixes);
			if (removalEvents.size() > MAX_REMOVAL_EVENTS) {
				removalEvents.remove(0);
				removalBase++;
			}
		}
		for (String scope : scopes) {
			notifyListeners(new Change(scope, Kind.CLEARED, null, null));
		}
		for (ClearListener l : clearListeners) l.onCleared(prefixes);
	}

	private static boolean startsWithAny(String scope, String[] prefixes) {
		for (String p : prefixes) {
			if (scope.startsWith(p)) return true;
		}
		return false;
	}

	private static void dropOrDefer(Entry e, Iterator<Entry> it) {
		if (e.running) {
			e.dropWhenDone = true;
			e.payload = null;
		} else {
			it.remove();
		}
	}

	private static void count(Map<String, Integer> counts, String scope) {
		Integer n = counts.get(scope);
		counts.put(scope, n == null ? 1 : n + 1);
	}

	void reset() {
		synchronized (lock) {
			entries.clear();
			notices.clear();
			removalEvents.clear();
			removalBase = 0L;
			removedOnLock.clear();
			removedForSpace.clear();
			queuedRetries.clear();
		}
		listeners.clear();
		clearListeners.clear();
		clock = System::currentTimeMillis;
	}

	private void dropExpired() {
		long now = clock.getAsLong();
		Iterator<Entry> it = entries.iterator();
		while (it.hasNext()) {
			Entry e = it.next();
			if (e.status == Status.RESOLVED
					&& now - e.resolvedAtMs > RESOLVED_TTL_MS) {
				it.remove();
			}
		}
	}

	private static boolean keptMedia(Entry e) {
		return !e.editable && !e.running && (e.status == Status.NOT_SENT
				|| e.status == Status.RETURNED);
	}

	private Set<String> keepWithinLimit() {
		Set<String> trimmed = new HashSet<>();
		while (true) {
			long kept = 0L;
			Entry largest = null;
			for (Entry e : entries) {
				if (!keptMedia(e)) continue;
				kept += e.weight;
				if (largest == null || e.weight > largest.weight) largest = e;
			}
			if (kept <= MAX_KEPT_BYTES || largest == null) return trimmed;
			entries.remove(largest);
			count(removedForSpace, largest.scope);
			trimmed.add(largest.scope);
		}
	}

	private void notifyRemoved(Set<String> scopes) {
		for (String scope : scopes) {
			notifyListeners(new Change(scope, Kind.REMOVED, null, null));
		}
	}

	private void execute(Entry e, Executor io) {
		try {
			io.execute(() -> run(e));
		} catch (RuntimeException rejected) {
			finish(e, Result.failed(null));
		}
	}

	private void run(Entry e) {
		Result r = null;
		try {
			r = e.attempt.run();
		} catch (RuntimeException ex) {
			r = Result.failed(null);
		} finally {
			finish(e, r == null ? Result.failed(null) : r);
		}
	}

	private void finish(Entry e, Result r) {
		Kind kind = kindOf(r.outcome);
		boolean dropped = false;
		boolean sent = r.outcome == Outcome.STORED
				|| r.outcome == Outcome.HANDED_OVER;
		Notice told = r.notice;
		Set<String> trimmed = new HashSet<>();
		synchronized (lock) {
			e.running = false;
			if (e.dropWhenDone || !entries.contains(e)) {
				entries.remove(e);
				if (e.scopeRemoved || e.session != session) return;
				if (!sent) {
					count(removedOnLock, e.scope);
				} else if (r.notice != null) {
					hold(e.scope, r.notice);
				}
				return;
			}
			if (sent) {
				if (heard(e.scope)) {
					e.identity = r.identity;
					e.handedOver = r.outcome == Outcome.HANDED_OVER;
					e.resolvedAtMs = clock.getAsLong();
					e.status = Status.RESOLVED;
				} else {
					entries.remove(e);
					dropped = true;
					told = null;
					if (r.notice != null) hold(e.scope, r.notice);
				}
			} else {
				if (!heard(e.scope)) {
					e.untold = r.notice;
					told = null;
				}
				e.status = r.outcome == Outcome.RETURNED
						? Status.RETURNED : Status.NOT_SENT;
				trimmed = keepWithinLimit();
			}
		}
		notifyListeners(new Change(e.scope, kind, dropped ? null : e, told));
		notifyRemoved(trimmed);
	}

	private void hold(String scope, Notice notice) {
		List<Notice> held = notices.get(scope);
		if (held == null) {
			held = new ArrayList<>();
			notices.put(scope, held);
		}
		held.add(notice);
	}

	private static Kind kindOf(Outcome o) {
		switch (o) {
			case STORED:
				return Kind.STORED;
			case HANDED_OVER:
				return Kind.HANDED_OVER;
			case RETURNED:
				return Kind.RETURNED;
			default:
				return Kind.FAILED;
		}
	}

	private void notifyListeners(Change c) {
		for (Registration r : listeners) {
			if (r.scopes.contains(c.scope)) r.listener.onPendingChanged(c);
		}
	}
}
