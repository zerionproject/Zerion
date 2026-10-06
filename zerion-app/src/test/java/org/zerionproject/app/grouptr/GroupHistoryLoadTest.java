package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupHistoryLoadTest {

	private static final long EPOCH = 5L;
	private static final int MB = 1024 * 1024;
	private static final long CACHE_BYTES_PER_GROUP = 24L * MB;

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] bKey = key((byte) 4);
	private final byte[] strangerKey = key((byte) 5);
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);
	private long clock = 1_000L;

	@Test
	public void aLoadHoldsOneStoredPostBodyAtATime() throws Exception {
		byte[] groupId = group();
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		byte[] big = new byte[MB];
		for (int i = 0; i < 40; i++) {
			node.storeAndPost(a, groupId, aKey, EPOCH, big, clock++);
		}
		node.dropFromMemory(groupId);
		node.forgetReads();

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertTrue("one read of the store handed over "
						+ node.largestBodyRead() + " bytes of post bodies",
				node.largestBodyRead() <= MB);
		assertEquals("the newest posts within the group's bytes show",
				CACHE_BYTES_PER_GROUP / MB, loaded.size());
	}

	@Test
	public void aLoadReadsNoPostsOfOtherGroups() throws Exception {
		byte[] viewed = group();
		byte[] other = group();
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(viewed);
		node.manager.getRecentPosts(other);
		byte[] big = new byte[MB];
		List<MessageId> otherPosts = new ArrayList<>();
		for (int i = 0; i < 30; i++) {
			otherPosts.add(node.storeAndPost(a, other, aKey, EPOCH, big,
					clock++));
		}
		for (int i = 0; i < 3; i++) {
			node.storeAndPost(a, viewed, aKey, EPOCH, new byte[16], clock++);
		}
		node.dropFromMemory(viewed);
		node.forgetReads();

		List<GroupTrPost> loaded = node.manager.getRecentPosts(viewed);

		assertEquals(3, loaded.size());
		for (MessageId id : otherPosts) {
			assertFalse("a post of another group was read",
					node.wasRead(id));
		}
		assertTrue("one read of the store handed over "
						+ node.largestBodyRead() + " bytes of post bodies",
				node.largestBodyRead() <= 16);
	}

	@Test
	public void postsThatMayNotShowTakeNoRoomOnALoad() throws Exception {
		byte[] groupId = group();
		ContactId a = node.addContact(aKey);
		ContactId stranger = node.addContact(strangerKey);
		node.manager.getRecentPosts(groupId);
		for (int i = 0; i < 10; i++) {
			node.storeAndPost(a, groupId, aKey, EPOCH, new byte[16], clock++);
		}
		for (int i = 0; i < 250; i++) {
			node.store(stranger, groupId, strangerKey, EPOCH, new byte[16],
					clock++, 0L);
		}
		node.dropFromMemory(groupId);

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals("every post of the member shows",
				10, countFrom(loaded, aKey));
		assertEquals(0, countFrom(loaded, strangerKey));
	}

	@Test
	public void aLocalPostSentToSeveralMembersShowsOnceAfterALoad()
			throws Exception {
		byte[] groupId = group();
		node.addContact(aKey);
		node.addContact(bKey);
		node.manager.getRecentPosts(groupId);
		node.manager.sendGroupPost(groupId, "one".getBytes("UTF-8"), 0L);
		node.now++;
		node.manager.sendGroupPost(groupId, "two".getBytes("UTF-8"), 0L);
		node.dropFromMemory(groupId);

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals(2, countFrom(loaded, localKey));
		assertEquals(2, loaded.size());
	}

	@Test
	public void expiredPostsAreRemovedReadingOneStoredPostBodyAtATime()
			throws Exception {
		byte[] groupId = group();
		ContactId a = node.addContact(aKey);
		byte[] big = new byte[MB];
		List<MessageId> expired = new ArrayList<>();
		List<MessageId> kept = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			expired.add(node.store(a, groupId, aKey, EPOCH, big, clock++,
					1_000L));
		}
		for (int i = 0; i < 10; i++) {
			kept.add(node.store(a, groupId, aKey, EPOCH, big, clock++, 0L));
		}
		for (int i = 0; i < 5; i++) {
			kept.add(node.store(a, groupId, aKey, EPOCH, big, clock++,
					10_000_000L));
		}
		node.forgetReads();

		node.manager.onDatabaseOpened(new Transaction(null, false));

		assertTrue("one read of the store handed over "
						+ node.largestBodyRead() + " bytes of post bodies",
				node.largestBodyRead() <= MB);
		for (MessageId id : expired) {
			assertFalse("an expired post is removed", node.isStored(id));
		}
		for (MessageId id : kept) {
			assertTrue("a post that has not expired is kept",
					node.isStored(id));
		}
	}

	private byte[] group() {
		byte[] groupId = getRandomId();
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		return groupId;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
