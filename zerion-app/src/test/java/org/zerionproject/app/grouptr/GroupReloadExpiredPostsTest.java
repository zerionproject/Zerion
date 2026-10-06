package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupReloadExpiredPostsTest {

	private static final long EPOCH = 5L;
	private static final long TIMER = 60_000L;

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] bKey = key((byte) 4);
	private final byte[] groupId = getRandomId();
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);

	@Test
	public void expiredPostsTakeNoRoomOnALoad() throws Exception {
		node.now = 10_000_000L;
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		open();
		for (int i = 0; i < 20; i++) {
			node.storeAndPost(b, groupId, bKey, EPOCH, new byte[16],
					1_000L + i);
		}
		for (int i = 0; i < 200; i++) {
			node.storeAndPost(a, groupId, aKey, EPOCH, new byte[16],
					node.now - 70_000L + i, TIMER);
		}
		node.now += 70_000L;
		List<GroupTrPost> live = node.manager.getRecentPosts(groupId);
		assertEquals(20, live.size());
		assertEquals(20, countFrom(live, bKey));

		node.dropFromMemory(groupId);
		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals("posts shown after a load, of 20 shown live",
				20, loaded.size());
		assertEquals("posts of the other member shown after a load",
				20, countFrom(loaded, bKey));
	}

	@Test
	public void postsThatExpiredWhileOutOfMemoryLeaveTheirRoomOnALoad()
			throws Exception {
		node.now = 10_000_000L;
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		for (int i = 0; i < 150; i++) {
			node.store(b, groupId, bKey, EPOCH, new byte[16], 1_000L + i, 0L);
		}
		List<MessageId> expired = new ArrayList<>();
		for (int i = 0; i < 150; i++) {
			expired.add(node.store(a, groupId, aKey, EPOCH, new byte[16],
					node.now - 70_000L + i, TIMER));
		}
		node.forgetReads();

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals("posts of the other member shown after a load, of 150"
				+ " stored", 150, countFrom(loaded, bKey));
		assertEquals(150, loaded.size());
		int readAgain = 0;
		for (MessageId id : expired) {
			if (node.timesRead(id) > 1) readAgain++;
		}
		assertEquals("expired posts read again to be shown", 0, readAgain);
	}

	private void open() {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		node.manager.getRecentPosts(groupId);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
