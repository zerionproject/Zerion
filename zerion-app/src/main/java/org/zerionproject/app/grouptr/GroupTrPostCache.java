package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrPostSummary;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.core.util.StringUtils.toHexString;

@ThreadSafe
@NotNullByDefault
final class GroupTrPostCache {

	static final int MAX_CACHED_POSTS_PER_GROUP = 200;
	private static final long MAX_CACHED_BYTES_PER_GROUP = 24L * 1024L * 1024L;
	private static final long MAX_CACHED_BYTES_TOTAL = 64L * 1024L * 1024L;
	private static final int MAX_BUFFERED_POSTS_PER_GROUP = 500;
	private static final long MAX_BUFFERED_BYTES_PER_GROUP =
			8L * 1024L * 1024L;
	private static final int MAX_BUFFERED_POSTS_PER_SENDER = 125;
	private static final long MAX_BUFFERED_BYTES_PER_SENDER =
			2L * 1024L * 1024L;
	private static final long MAX_BUFFERED_BYTES_TOTAL = 16L * 1024L * 1024L;

	static final class BufferedPost {

		final GroupTrPost post;
		final byte[] identity;
		@Nullable
		final MessageId id;

		BufferedPost(GroupTrPost post, byte[] identity,
				@Nullable MessageId id) {
			this.post = post;
			this.identity = identity;
			this.id = id;
		}
	}

	private final Function<String, ReentrantLock> groupLocks;
	private final Map<String, ArrayDeque<GroupTrPost>> postCache =
			new ConcurrentHashMap<>();
	private final Map<String, TreeMap<Long, List<BufferedPost>>>
			futureBuffer = new ConcurrentHashMap<>();
	private final Set<String> historyLoaded = ConcurrentHashMap.newKeySet();
	private final Map<String, Remembered> summaries =
			new ConcurrentHashMap<>();
	private final Object summaryLock = new Object();
	private long summaryGeneration = 0L;

	static final class Remembered {

		@Nullable
		final GroupTrPostSummary summary;
		final String members;

		Remembered(@Nullable GroupTrPostSummary summary, String members) {
			this.summary = summary;
			this.members = members;
		}
	}

	GroupTrPostCache(Function<String, ReentrantLock> groupLocks) {
		this.groupLocks = groupLocks;
	}

	@Nullable
	Remembered remembered(String key) {
		return summaries.get(key);
	}

	long summaryGeneration() {
		synchronized (summaryLock) {
			return summaryGeneration;
		}
	}

	boolean remember(String key, @Nullable GroupTrPostSummary summary,
			String members, long generation) {
		synchronized (summaryLock) {
			if (generation != summaryGeneration) return false;
			summaries.put(key, new Remembered(summary, members));
			return true;
		}
	}

	void forgetSummary(String key) {
		synchronized (summaryLock) {
			summaryGeneration++;
			summaries.remove(key);
		}
	}

	void forgetAllSummaries() {
		synchronized (summaryLock) {
			summaryGeneration++;
			summaries.clear();
		}
	}

	private void dropSummaryOfThisGroup(String key) {
		summaries.remove(key);
	}

	@Nullable
	ArrayDeque<GroupTrPost> queue(String key) {
		return postCache.get(key);
	}

	ArrayDeque<GroupTrPost> queueFor(String key) {
		return postCache.computeIfAbsent(key, k -> new ArrayDeque<>());
	}

	boolean isHistoryLoaded(String key) {
		return historyLoaded.contains(key);
	}

	void markHistoryLoaded(String key) {
		historyLoaded.add(key);
	}

	void forgetHistory(String key) {
		historyLoaded.remove(key);
		forgetSummary(key);
	}

	void forget(String key) {
		postCache.remove(key);
		futureBuffer.remove(key);
		historyLoaded.remove(key);
		forgetSummary(key);
	}

	void purgeExpired(long now) {
		for (Map.Entry<String, ArrayDeque<GroupTrPost>> e
				: postCache.entrySet()) {
			ArrayDeque<GroupTrPost> q = e.getValue();
			synchronized (q) {
				Iterator<GroupTrPost> it = q.iterator();
				while (it.hasNext()) {
					if (it.next().isExpiredAt(now)) it.remove();
				}
			}
		}
		for (Map.Entry<String, TreeMap<Long, List<BufferedPost>>> e
				: futureBuffer.entrySet()) {
			TreeMap<Long, List<BufferedPost>> tm = e.getValue();
			synchronized (tm) {
				Iterator<Map.Entry<Long, List<BufferedPost>>> entries =
						tm.entrySet().iterator();
				while (entries.hasNext()) {
					List<BufferedPost> list = entries.next().getValue();
					list.removeIf(b -> b.post.isExpiredAt(now));
					if (list.isEmpty()) entries.remove();
				}
			}
		}
	}

	void deliver(String key, GroupTrPost p) {
		dropSummaryOfThisGroup(key);
		ArrayDeque<GroupTrPost> q = queueFor(key);
		synchronized (q) {
			for (GroupTrPost existing : q) {
				if (isSamePost(existing, p)) return;
			}
			q.addLast(p);
			capCache(q);
		}
		capTotal(key);
	}

	void addLocal(String key, GroupTrPost p) {
		dropSummaryOfThisGroup(key);
		ArrayDeque<GroupTrPost> q = queueFor(key);
		synchronized (q) {
			q.addLast(p);
			capCache(q);
		}
		capTotal(key);
	}

	Map<String, byte[]> removePosts(String key, Set<String> postIds,
			@Nullable byte[] sender) {
		forgetSummary(key);
		Map<String, byte[]> removed = new HashMap<>();
		ArrayDeque<GroupTrPost> q = postCache.get(key);
		if (q == null) return removed;
		synchronized (q) {
			Iterator<GroupTrPost> it = q.iterator();
			while (it.hasNext()) {
				GroupTrPost p = it.next();
				byte[] id = p.getPostId();
				if (id == null || !postIds.contains(toHexString(id))) continue;
				if (sender != null
						&& !Arrays.equals(p.getSenderPubKey(), sender)) {
					continue;
				}
				removed.put(toHexString(id), p.getSenderPubKey());
				it.remove();
			}
		}
		return removed;
	}

	List<BufferedPost> removeBuffered(String key, Set<String> identities,
			@Nullable byte[] sender) {
		List<BufferedPost> removed = new ArrayList<>();
		TreeMap<Long, List<BufferedPost>> bucket = futureBuffer.get(key);
		if (bucket == null) return removed;
		synchronized (bucket) {
			Iterator<Map.Entry<Long, List<BufferedPost>>> entries =
					bucket.entrySet().iterator();
			while (entries.hasNext()) {
				List<BufferedPost> list = entries.next().getValue();
				Iterator<BufferedPost> it = list.iterator();
				while (it.hasNext()) {
					BufferedPost b = it.next();
					if (!identities.contains(toHexString(b.identity))) continue;
					if (sender != null && !Arrays.equals(
							b.post.getSenderPubKey(), sender)) {
						continue;
					}
					removed.add(b);
					it.remove();
				}
				if (list.isEmpty()) entries.remove();
			}
			if (bucket.isEmpty()) futureBuffer.remove(key, bucket);
		}
		return removed;
	}

	static boolean isSamePost(GroupTrPost a, GroupTrPost b) {
		return a.getEpoch() == b.getEpoch()
				&& a.getTimestamp() == b.getTimestamp()
				&& Arrays.equals(a.getSenderPubKey(), b.getSenderPubKey())
				&& Arrays.equals(a.getBody(), b.getBody());
	}

	void buffer(String key, BufferedPost b) {
		TreeMap<Long, List<BufferedPost>> bucket =
				futureBuffer.computeIfAbsent(key, k -> new TreeMap<>());
		synchronized (bucket) {
			bucket.computeIfAbsent(b.post.getEpoch(),
					k -> new ArrayList<>()).add(b);
			capBuffer(bucket, b.post.getSenderPubKey());
		}
		capBufferTotal();
	}

	private static void capBuffer(TreeMap<Long, List<BufferedPost>> bucket,
			byte[] sender) {
		while (true) {
			int count = 0;
			long bytes = 0;
			int senderCount = 0;
			long senderBytes = 0;
			for (List<BufferedPost> list : bucket.values()) {
				for (BufferedPost b : list) {
					long size = bodyLength(b.post);
					count++;
					bytes += size;
					if (Arrays.equals(b.post.getSenderPubKey(), sender)) {
						senderCount++;
						senderBytes += size;
					}
				}
			}
			if (senderCount > 1
					&& (senderCount > MAX_BUFFERED_POSTS_PER_SENDER
					|| senderBytes > MAX_BUFFERED_BYTES_PER_SENDER)) {
				removeFurthest(bucket, sender);
			} else if (count > 1 && (count > MAX_BUFFERED_POSTS_PER_GROUP
					|| bytes > MAX_BUFFERED_BYTES_PER_GROUP)) {
				removeFurthest(bucket, null);
			} else {
				return;
			}
		}
	}

	private void capBufferTotal() {
		while (true) {
			long total = 0;
			String largest = null;
			long largestBytes = 0;
			for (Map.Entry<String, TreeMap<Long, List<BufferedPost>>> e
					: futureBuffer.entrySet()) {
				long b;
				synchronized (e.getValue()) {
					b = bufferedBytes(e.getValue());
				}
				total += b;
				if (largest == null || b > largestBytes) {
					largest = e.getKey();
					largestBytes = b;
				}
			}
			if (largest == null || total <= MAX_BUFFERED_BYTES_TOTAL) return;
			TreeMap<Long, List<BufferedPost>> bucket = futureBuffer.get(largest);
			if (bucket == null) continue;
			synchronized (bucket) {
				if (!removeFurthest(bucket, null)) return;
				if (bucket.isEmpty()) futureBuffer.remove(largest, bucket);
			}
		}
	}

	private static boolean removeFurthest(
			TreeMap<Long, List<BufferedPost>> bucket, @Nullable byte[] sender) {
		for (Map.Entry<Long, List<BufferedPost>> entry
				: bucket.descendingMap().entrySet()) {
			List<BufferedPost> list = entry.getValue();
			for (int i = 0; i < list.size(); i++) {
				if (sender != null && !Arrays.equals(
						list.get(i).post.getSenderPubKey(), sender)) {
					continue;
				}
				list.remove(i);
				if (list.isEmpty()) bucket.remove(entry.getKey());
				return true;
			}
		}
		return false;
	}

	private static long bufferedBytes(
			TreeMap<Long, List<BufferedPost>> bucket) {
		long bytes = 0;
		for (List<BufferedPost> list : bucket.values()) {
			for (BufferedPost b : list) bytes += bodyLength(b.post);
		}
		return bytes;
	}

	List<BufferedPost> release(String key, long throughEpoch) {
		TreeMap<Long, List<BufferedPost>> bucket = futureBuffer.get(key);
		if (bucket == null) return Collections.emptyList();
		List<BufferedPost> released = new ArrayList<>();
		synchronized (bucket) {
			Iterator<Map.Entry<Long, List<BufferedPost>>> it =
					bucket.entrySet().iterator();
			while (it.hasNext()) {
				Map.Entry<Long, List<BufferedPost>> entry = it.next();
				if (entry.getKey() <= throughEpoch) {
					released.addAll(entry.getValue());
					it.remove();
				} else {
					break;
				}
			}
			if (bucket.isEmpty()) futureBuffer.remove(key);
		}
		return released;
	}

	static void capCache(ArrayDeque<GroupTrPost> q) {
		if (q.size() <= MAX_CACHED_POSTS_PER_GROUP
				&& cachedBytes(q, false) <= MAX_CACHED_BYTES_PER_GROUP) {
			return;
		}
		int n = q.size();
		int[] senders = new int[n];
		long[] sizes = new long[n];
		Map<String, Integer> senderIds = new HashMap<>();
		int i = 0;
		for (GroupTrPost p : q) {
			senders[i] = senderId(senderIds, p.getSenderPubKey());
			sizes[i] = bodyLength(p);
			i++;
		}
		boolean[] keep = keepWithinGroupBounds(senders, sizes, n);
		List<GroupTrPost> kept = new ArrayList<>(n);
		i = 0;
		for (GroupTrPost p : q) {
			if (keep[i++]) kept.add(p);
		}
		q.clear();
		q.addAll(kept);
	}

	static int senderId(Map<String, Integer> senderIds, byte[] senderPub) {
		String hex = toHexString(senderPub);
		Integer id = senderIds.get(hex);
		if (id == null) {
			id = senderIds.size();
			senderIds.put(hex, id);
		}
		return id;
	}

	static boolean[] keepWithinGroupBounds(int[] senders, long[] sizes,
			int n) {
		boolean[] keep = new boolean[n];
		Arrays.fill(keep, true);
		int senderCount = 0;
		for (int i = 0; i < n; i++) {
			senderCount = Math.max(senderCount, senders[i] + 1);
		}
		long[] heldPosts = new long[senderCount];
		long[] heldBytes = new long[senderCount];
		int[] oldest = new int[senderCount];
		Arrays.fill(oldest, -1);
		int[] nextOfSender = new int[n];
		long count = n;
		long bytes = 0;
		for (int i = n - 1; i >= 0; i--) {
			int s = senders[i];
			heldPosts[s]++;
			heldBytes[s] += sizes[i];
			bytes += sizes[i];
			if (i == n - 1) continue;
			nextOfSender[i] = oldest[s];
			oldest[s] = i;
		}
		for (int measure = 0; measure < 2; measure++) {
			long[] held = measure == 0 ? heldPosts : heldBytes;
			TreeSet<Integer> most = new TreeSet<>(
					(x, y) -> held[x] != held[y]
							? Long.compare(held[y], held[x])
							: Integer.compare(oldest[x], oldest[y]));
			for (int s = 0; s < senderCount; s++) {
				if (oldest[s] >= 0) most.add(s);
			}
			while (count > 1 && (measure == 0
					? count > MAX_CACHED_POSTS_PER_GROUP
					: bytes > MAX_CACHED_BYTES_PER_GROUP)) {
				Integer s = most.pollFirst();
				if (s == null) break;
				int i = oldest[s];
				keep[i] = false;
				oldest[s] = nextOfSender[i];
				count--;
				bytes -= sizes[i];
				heldPosts[s]--;
				heldBytes[s] -= sizes[i];
				if (oldest[s] >= 0) most.add(s);
			}
		}
		return keep;
	}

	void capTotal(String keep) {
		long total = 0;
		for (ArrayDeque<GroupTrPost> q : postCache.values()) {
			synchronized (q) {
				total += cachedBytes(q, false);
			}
		}
		Set<String> passed = new HashSet<>();
		passed.add(keep);
		while (total > MAX_CACHED_BYTES_TOTAL) {
			String largest = null;
			long largestBytes = 0;
			for (Map.Entry<String, ArrayDeque<GroupTrPost>> e
					: postCache.entrySet()) {
				if (passed.contains(e.getKey())) continue;
				long b;
				synchronized (e.getValue()) {
					b = cachedBytes(e.getValue(), true);
				}
				if (largest == null || b > largestBytes) {
					largest = e.getKey();
					largestBytes = b;
				}
			}
			if (largest == null) return;
			passed.add(largest);
			ReentrantLock lock = groupLocks.apply(largest);
			if (!lock.tryLock()) continue;
			try {
				ArrayDeque<GroupTrPost> q = postCache.get(largest);
				if (q == null) continue;
				synchronized (q) {
					total -= cachedBytes(q, true);
					q.removeIf(p -> !p.isLocal());
				}
				historyLoaded.remove(largest);
			} finally {
				lock.unlock();
			}
		}
	}

	private static long cachedBytes(Collection<GroupTrPost> posts,
			boolean remoteOnly) {
		long bytes = 0;
		for (GroupTrPost p : posts) {
			if (remoteOnly && p.isLocal()) continue;
			bytes += bodyLength(p);
		}
		return bytes;
	}

	static long bodyLength(@Nullable GroupTrPost p) {
		if (p == null) return 0;
		byte[] b = p.getBody();
		return b == null ? 0 : b.length;
	}
}
