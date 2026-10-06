package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.contact.ContactId;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupReloadSharesTest {

	private static final long EPOCH = 5L;
	private static final int MB = 1024 * 1024;
	private static final int LARGE_POST = 10 * MB - 8 * 1024;
	private static final long DAY = 24L * 60 * 60 * 1000;

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] bKey = key((byte) 4);
	private final byte[] hKey = key((byte) 6);
	private final byte[] groupId = getRandomId();
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);
	private long clock = 1_000L;

	@Test
	public void aFewLargePostsOfOneMemberLeaveTheOthersTheirPostsOnALoad()
			throws Exception {
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		open();
		for (int i = 0; i < 20; i++) {
			node.storeAndPost(b, groupId, bKey, EPOCH, new byte[16], clock++);
		}
		byte[] large = new byte[LARGE_POST];
		for (int i = 0; i < 3; i++) {
			node.storeAndPost(a, groupId, aKey, EPOCH, large, clock++);
		}
		List<GroupTrPost> live = node.manager.getRecentPosts(groupId);
		assertEquals(20, countFrom(live, bKey));
		assertEquals(2, countFrom(live, aKey));

		node.dropFromMemory(groupId);
		node.forgetReads();
		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals("posts of the other member shown after a load, of 20"
				+ " shown live", 20, countFrom(loaded, bKey));
		assertEquals("large posts shown after a load, of 2 shown live",
				2, countFrom(loaded, aKey));
		assertTrue("one read of the store handed over "
						+ node.largestBodyRead() + " bytes of post bodies",
				node.largestBodyRead() <= LARGE_POST);
	}

	@Test
	public void postsDatedAheadLeaveTheOthersTheirPostsOnALoad()
			throws Exception {
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		open();
		for (int i = 0; i < 200; i++) {
			node.storeAndPost(a, groupId, aKey, EPOCH, new byte[16],
					node.now + DAY - 1_000L + i);
		}
		for (int i = 0; i < 50; i++) {
			node.storeAndPost(b, groupId, bKey, EPOCH, new byte[16],
					node.now + 60_000L + i);
		}
		List<GroupTrPost> live = node.manager.getRecentPosts(groupId);
		assertEquals(50, countFrom(live, bKey));
		assertEquals(150, countFrom(live, aKey));

		node.dropFromMemory(groupId);
		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals("posts of the other member shown after a load, of 50"
				+ " shown live", 50, countFrom(loaded, bKey));
		assertEquals("posts dated ahead shown after a load, of 150 shown live",
				150, countFrom(loaded, aKey));
	}

	@Test
	public void aLoadGivesAFloodTheShareTheLiveViewGivesIt()
			throws Exception {
		ContactId h = node.addContact(hKey);
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		open();
		for (int i = 0; i < 20; i++) {
			node.storeAndPost(b, groupId, bKey, EPOCH, new byte[16], clock++);
		}
		for (int i = 0; i < 170; i++) {
			node.storeAndPost(h, groupId, hKey, EPOCH, new byte[16], clock++);
		}
		for (int i = 0; i < 1000; i++) {
			node.storeAndPost(a, groupId, aKey, EPOCH, new byte[16], clock++);
		}
		List<GroupTrPost> live = node.manager.getRecentPosts(groupId);
		assertEquals(20, countFrom(live, bKey));
		assertEquals(90, countFrom(live, hKey));
		assertEquals(90, countFrom(live, aKey));

		node.dropFromMemory(groupId);
		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals("posts of the quiet member shown after a load, of 20"
				+ " shown live", 20, countFrom(loaded, bKey));
		assertEquals("posts of the active member shown after a load, of 90"
				+ " shown live", 90, countFrom(loaded, hKey));
		assertEquals("posts of the flooding member shown after a load, of 90"
				+ " shown live", 90, countFrom(loaded, aKey));
	}

	private void open() {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L),
				member(hKey, 1L));
		node.manager.getRecentPosts(groupId);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
