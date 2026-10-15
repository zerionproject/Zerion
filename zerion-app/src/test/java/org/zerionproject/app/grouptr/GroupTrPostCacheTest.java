package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GroupTrPostCacheTest {

	private static final int MB = 1024 * 1024;
	private static final byte[] GROUP = new byte[32];

	private final Map<String, ReentrantLock> locks = new HashMap<>();
	private final GroupTrPostCache cache = new GroupTrPostCache(
			k -> locks.computeIfAbsent(k, x -> new ReentrantLock()));
	private long clock = 1_000L;

	private static byte[] key(int b) {
		byte[] k = new byte[32];
		Arrays.fill(k, (byte) b);
		return k;
	}

	private GroupTrPost post(int sender, int bytes, long epoch) {
		return new GroupTrPost(GROUP, key(sender), "n", new byte[bytes],
				clock++, epoch, false);
	}

	private static int from(Iterable<GroupTrPost> posts, int sender) {
		int n = 0;
		for (GroupTrPost p : posts) {
			if (Arrays.equals(p.getSenderPubKey(), key(sender))) n++;
		}
		return n;
	}

	private List<GroupTrPost> cached(String key) {
		ArrayDeque<GroupTrPost> q = cache.queue(key);
		return q == null ? new ArrayList<>() : new ArrayList<>(q);
	}

	@Test
	public void aDeliveredPostIsKeptOnceButLocalPostsAreAlwaysAdded() {
		GroupTrPost p = post(1, 8, 1);
		cache.deliver("g", p);
		cache.deliver("g", new GroupTrPost(GROUP, key(1), "other name",
				new byte[8], p.getTimestamp(), 1, false));
		assertEquals(1, cached("g").size());
		GroupTrPost mine = new GroupTrPost(GROUP, key(2), "me", new byte[4],
				clock, 1, true);
		cache.addLocal("g", mine);
		cache.addLocal("g", mine);
		assertEquals(3, cached("g").size());
	}

	@Test
	public void overTheCountTheBiggestSenderLosesItsOldest() {
		for (int i = 0; i < 3; i++) cache.deliver("g", post(1, 1, 1));
		for (int i = 0; i < 199; i++) cache.deliver("g", post(2, 1, 1));
		List<GroupTrPost> kept = cached("g");
		assertEquals(GroupTrPostCache.MAX_CACHED_POSTS_PER_GROUP, kept.size());
		assertEquals(3, from(kept, 1));
		assertEquals(197, from(kept, 2));
		assertEquals("the oldest of the biggest sender went first",
				1_000L + 5, kept.get(3).getTimestamp());
	}

	@Test
	public void overTheBytesTheBiggestSenderLosesItsOldest() {
		cache.deliver("g", post(1, 16, 1));
		for (int i = 0; i < 25; i++) cache.deliver("g", post(2, MB, 1));
		List<GroupTrPost> kept = cached("g");
		assertEquals(1, from(kept, 1));
		assertEquals(23, from(kept, 2));
	}

	@Test
	public void theTotalBudgetEvictsOtherMembersPostsOfTheLargestGroup() {
		GroupTrPost mine = new GroupTrPost(GROUP, key(9), "me", new byte[8],
				clock++, 1, true);
		cache.addLocal("big", mine);
		for (int i = 0; i < 20; i++) cache.deliver("big", post(1, MB, 1));
		cache.markHistoryLoaded("big");
		for (int g = 0; g < 3; g++) {
			for (int i = 0; i < 15; i++) {
				cache.deliver("g" + g, post(2, MB, 1));
			}
		}
		List<GroupTrPost> big = cached("big");
		assertEquals(1, big.size());
		assertTrue(big.get(0).isLocal());
		assertFalse("an evicted group loads its history again",
				cache.isHistoryLoaded("big"));
	}

	@Test
	public void aGroupLockedByAnotherThreadIsNotEvicted() throws Exception {
		for (int i = 0; i < 20; i++) cache.deliver("big", post(1, MB, 1));
		ReentrantLock bigLock =
				locks.computeIfAbsent("big", x -> new ReentrantLock());
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		Thread holder = new Thread(() -> {
			bigLock.lock();
			try {
				held.countDown();
				release.await(30, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				bigLock.unlock();
			}
		});
		holder.start();
		assertTrue(held.await(30, TimeUnit.SECONDS));
		try {
			for (int g = 0; g < 3; g++) {
				for (int i = 0; i < 15; i++) {
					cache.deliver("g" + g, post(2, MB, 1));
				}
			}
			assertEquals(20, cached("big").size());
		} finally {
			release.countDown();
			holder.join();
		}
	}

	@Test
	public void bufferedPostsAreReleasedInEpochOrderUpToTheGivenEpoch() {
		MessageId id = new MessageId(new byte[32]);
		cache.buffer("g", new GroupTrPostCache.BufferedPost(post(1, 1, 12),
				new byte[] {12}, id));
		cache.buffer("g", new GroupTrPostCache.BufferedPost(post(1, 1, 10),
				new byte[] {10}, null));
		cache.buffer("g", new GroupTrPostCache.BufferedPost(post(1, 1, 11),
				new byte[] {11}, null));
		assertTrue(cache.release("other", 99).isEmpty());
		List<GroupTrPostCache.BufferedPost> first = cache.release("g", 11);
		assertEquals(2, first.size());
		assertEquals(10, first.get(0).post.getEpoch());
		assertEquals(11, first.get(1).post.getEpoch());
		List<GroupTrPostCache.BufferedPost> rest = cache.release("g", 100);
		assertEquals(1, rest.size());
		assertEquals(id, rest.get(0).id);
		assertTrue(cache.release("g", 100).isEmpty());
	}

	@Test
	public void aBufferingSenderIsCappedBeforeTheGroup() {
		for (int i = 0; i < 3; i++) {
			cache.buffer("g", new GroupTrPostCache.BufferedPost(
					post(1, 1, 20), new byte[] {1}, null));
		}
		for (int i = 0; i < 200; i++) {
			cache.buffer("g", new GroupTrPostCache.BufferedPost(
					post(2, 1, 20 + (i % 3)), new byte[] {2}, null));
		}
		List<GroupTrPostCache.BufferedPost> all = cache.release("g", 1000);
		List<GroupTrPost> posts = new ArrayList<>();
		for (GroupTrPostCache.BufferedPost b : all) posts.add(b.post);
		assertEquals(3, from(posts, 1));
		assertEquals(125, from(posts, 2));
	}

	@Test
	public void expiredPostsArePurgedFromMemoryAndBuffer() {
		GroupTrPost live = post(1, 1, 1);
		GroupTrPost dying = new GroupTrPost(GROUP, key(1), "n", new byte[1],
				clock++, 1, false, 100L);
		cache.deliver("g", live);
		cache.deliver("g", dying);
		cache.buffer("g", new GroupTrPostCache.BufferedPost(
				new GroupTrPost(GROUP, key(1), "n", new byte[1], clock++, 9,
						false, 100L), new byte[] {1}, null));
		cache.purgeExpired(dying.getTimestamp() + 200L);
		assertEquals(1, cached("g").size());
		assertTrue(cache.release("g", 99).isEmpty());
	}

	@Test
	public void forgettingAGroupDropsEverythingHeldForIt() {
		cache.deliver("g", post(1, 1, 1));
		cache.buffer("g", new GroupTrPostCache.BufferedPost(post(1, 1, 9),
				new byte[] {1}, null));
		cache.markHistoryLoaded("g");
		cache.forget("g");
		assertNull(cache.queue("g"));
		assertFalse(cache.isHistoryLoaded("g"));
		assertTrue(cache.release("g", 99).isEmpty());
	}
}
