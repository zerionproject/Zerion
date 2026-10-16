package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.contact.ContactId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupPostCacheTest {

	private static final long EPOCH = 5L;
	private static final int MB = 1024 * 1024;
	private static final long CACHE_BYTES_TOTAL = 64L * MB;

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] honestKey = key((byte) 3);
	private final byte[] departedKey = key((byte) 9);
	private long clock = 1_000L;

	@Test
	public void postsWithinTheEpochToleranceShowAndLaterOnesWait()
			throws Exception {
		byte[] groupId = getRandomId();
		GroupTrTestNode node = node(groupId);
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId honest = node.addContact(honestKey);
		node.manager.getRecentPosts(groupId);

		node.post(honest, groupId, honestKey, EPOCH + 5,
				"near".getBytes("UTF-8"), clock++);
		node.post(honest, groupId, honestKey, EPOCH + 7,
				"far".getBytes("UTF-8"), clock++);
		assertEquals(1, countFrom(node.manager.getRecentPosts(groupId),
				honestKey));
		assertEquals(1, node.buffered().size());

		node.removed(fromCreator, groupId, departedKey, EPOCH);
		node.commit(fromCreator, groupId, EPOCH);
		assertEquals("one epoch later the far post still waits",
				1, node.buffered().size());
		assertEquals(1, countFrom(node.manager.getRecentPosts(groupId),
				honestKey));

		node.removed(fromCreator, groupId, departedKey, EPOCH + 1);
		node.commit(fromCreator, groupId, EPOCH + 1);
		assertEquals(0, node.buffered().size());
		List<GroupTrPost> shown = node.manager.getRecentPosts(groupId);
		assertEquals(2, countFrom(shown, honestKey));
		assertEquals("far", new String(shown.get(shown.size() - 1).getBody(),
				"UTF-8"));
	}

	@Test
	public void theTotalBudgetDropsOnlyOtherMembersPostsFromMemory()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		List<byte[]> groups = new ArrayList<>();
		for (int g = 0; g < 8; g++) {
			byte[] groupId = getRandomId();
			groups.add(groupId);
			node.putGroup(groupId, creatorKey, EPOCH, members());
		}
		ContactId honest = node.addContact(honestKey);
		node.addContact(creatorKey);
		for (byte[] groupId : groups) node.manager.getRecentPosts(groupId);
		byte[] first = groups.get(0);
		node.manager.sendGroupPost(first, "mine one".getBytes("UTF-8"), 0L);
		node.manager.sendGroupPost(first, "mine two".getBytes("UTF-8"), 0L);
		byte[] big = new byte[MB];
		for (int i = 0; i < 13; i++) {
			node.storeAndPost(honest, first, honestKey, EPOCH, big, clock++);
		}
		for (byte[] groupId : groups.subList(1, groups.size())) {
			for (int i = 0; i < 12; i++) {
				node.storeAndPost(honest, groupId, honestKey, EPOCH, big,
						clock++);
				assertTrue(node.heldBytes() <= CACHE_BYTES_TOTAL);
			}
		}

		List<GroupTrPost> kept = node.cached(first);
		assertEquals("the largest group gave up its other members' posts",
				0, countFrom(kept, honestKey));
		assertEquals("its own posts stay in memory", 2, kept.size());
		for (GroupTrPost p : kept) assertTrue(p.isLocal());

		List<GroupTrPost> shown = node.manager.getRecentPosts(first);
		assertEquals(13, countFrom(shown, honestKey));
		assertEquals(2, countFrom(shown, localKey));
	}

	@Test
	public void aRemovedGroupLeavesNothingInMemory() throws Exception {
		byte[] groupId = getRandomId();
		GroupTrTestNode node = node(groupId);
		ContactId honest = node.addContact(honestKey);
		node.manager.getRecentPosts(groupId);
		node.post(honest, groupId, honestKey, EPOCH, new byte[16], clock++);
		node.post(honest, groupId, honestKey, EPOCH + 9, new byte[16],
				clock++);
		assertEquals(1, node.cached(groupId).size());
		assertEquals(1, node.buffered().size());

		node.manager.removeFromDevice(groupId);

		assertEquals(0, node.cached(groupId).size());
		assertEquals(0, node.buffered().size());
		assertEquals(0, node.heldBytes());
	}

	private GroupTrTestNode node(byte[] groupId) {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, members());
		return node;
	}

	private GroupTrMember[] members() {
		return new GroupTrMember[] {creator(creatorKey), member(localKey, 1L),
				member(honestKey, 2L)};
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
