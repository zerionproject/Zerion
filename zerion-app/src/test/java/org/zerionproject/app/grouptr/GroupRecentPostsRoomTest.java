package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.contact.ContactId;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.bytesFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupRecentPostsRoomTest {

	private static final long EPOCH = 5L;
	private static final int MB = 1024 * 1024;
	private static final int CACHE_POSTS_PER_GROUP = 200;
	private static final long CACHE_BYTES_PER_GROUP = 24L * MB;

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] bKey = key((byte) 4);
	private final byte[] groupId = getRandomId();
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);
	private final Map<Integer, byte[]> bodies = new HashMap<>();
	private ContactId a;
	private ContactId b;
	private long clock = 1_000L;

	@Before
	public void setUp() {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		a = node.addContact(aKey);
		b = node.addContact(bKey);
		node.manager.getRecentPosts(groupId);
	}

	@Test
	public void anActiveMembersPostsAllShowWhileTheGroupHasRoom()
			throws Exception {
		for (int i = 0; i < 10; i++) send(b, bKey, 16);
		for (int i = 0; i < 150; i++) send(a, aKey, 16);

		List<GroupTrPost> live = node.manager.getRecentPosts(groupId);
		assertEquals("every post of the active member shows",
				150, countFrom(live, aKey));
		assertEquals(10, countFrom(live, bKey));

		node.dropFromMemory(groupId);
		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);
		assertEquals("every post of the active member shows after a load",
				150, countFrom(loaded, aKey));
		assertEquals(10, countFrom(loaded, bKey));
	}

	@Test
	public void mediaFromOneMemberAllShowsWhileTheGroupHasRoom()
			throws Exception {
		for (int i = 0; i < 4; i++) send(a, aKey, 5 * MB);

		assertEquals("every media post shows", 4,
				countFrom(node.manager.getRecentPosts(groupId), aKey));

		node.dropFromMemory(groupId);
		assertEquals("every media post shows after a load", 4,
				countFrom(node.manager.getRecentPosts(groupId), aKey));
	}

	@Test
	public void overTheCountBoundTheMemberHoldingTheMostGivesUpItsOldest()
			throws Exception {
		for (int i = 0; i < 30; i++) send(b, bKey, 16);
		List<Long> sentByA = new ArrayList<>();
		for (int i = 0; i < 190; i++) sentByA.add(send(a, aKey, 16));

		List<GroupTrPost> shown = node.manager.getRecentPosts(groupId);
		assertEquals(CACHE_POSTS_PER_GROUP, shown.size());
		assertEquals("the other member keeps every post",
				30, countFrom(shown, bKey));
		assertEquals(170, countFrom(shown, aKey));
		assertEquals("the member holding the most gave up its oldest posts",
				(long) sentByA.get(20), oldestFrom(shown, aKey));
	}

	@Test
	public void overTheByteBoundTheMemberHoldingTheMostGivesUpItsOldest()
			throws Exception {
		for (int i = 0; i < 2; i++) send(b, bKey, 5 * MB);
		List<Long> sentByA = new ArrayList<>();
		for (int i = 0; i < 4; i++) sentByA.add(send(a, aKey, 5 * MB));

		List<GroupTrPost> shown = node.manager.getRecentPosts(groupId);
		assertTrue(bytesFrom(shown, null) <= CACHE_BYTES_PER_GROUP);
		assertEquals("the other member keeps every post",
				2, countFrom(shown, bKey));
		assertEquals(2, countFrom(shown, aKey));
		assertEquals("the member holding the most gave up its oldest posts",
				(long) sentByA.get(2), oldestFrom(shown, aKey));
	}

	@Test
	public void aLoadKeepsEachMembersShareWithinTheGroupBounds()
			throws Exception {
		for (int i = 0; i < 120; i++) send(a, aKey, 16);
		for (int i = 0; i < 120; i++) send(b, bKey, 16);
		List<GroupTrPost> live = node.manager.getRecentPosts(groupId);
		assertEquals(100, countFrom(live, aKey));
		assertEquals(100, countFrom(live, bKey));
		node.dropFromMemory(groupId);

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);
		assertEquals(CACHE_POSTS_PER_GROUP, loaded.size());
		assertEquals("each member keeps the share it has in the live view",
				100, countFrom(loaded, bKey));
		assertEquals(100, countFrom(loaded, aKey));
	}

	@Test
	public void aLoadKeepsTheNewestMediaWithinTheGroupByteBound()
			throws Exception {
		List<Long> sent = new ArrayList<>();
		for (int i = 0; i < 6; i++) sent.add(send(a, aKey, 5 * MB));
		node.dropFromMemory(groupId);

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);
		assertEquals("the newest media within the group's bytes show",
				4, loaded.size());
		assertEquals((long) sent.get(2), oldestFrom(loaded, aKey));
	}

	private long send(ContactId from, byte[] sender, int size) {
		long t = clock++;
		byte[] body = bodies.computeIfAbsent(size, byte[]::new);
		node.storeAndPost(from, groupId, sender, EPOCH, body, t);
		return t;
	}

	private static long oldestFrom(List<GroupTrPost> posts, byte[] sender) {
		long oldest = Long.MAX_VALUE;
		for (GroupTrPost p : posts) {
			if (Arrays.equals(p.getSenderPubKey(), sender)) {
				oldest = Math.min(oldest, p.getTimestamp());
			}
		}
		return oldest;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
