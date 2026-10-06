package org.zerionproject.app.grouptr;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupPendingScanTest {

	private static final long EPOCH = 5L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] strangerKey = key((byte) 4);
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);

	@Test
	public void holdingPendingPostsDoesNotReadTheChatAtEveryArrival()
			throws Exception {
		group();
		ContactId stranger = node.addContact(strangerKey);
		node.manager.getRecentPosts(groupId);
		int before = node.queries();

		for (int i = 0; i < 20; i++) {
			node.receive(stranger, groupId, strangerKey, EPOCH,
					new byte[] {(byte) i}, node.now + i, 0L, SIG);
		}

		int reads = node.queries() - before;
		assertTrue("the chat was read " + reads + " times for 20 pending"
				+ " posts", reads <= 1);
	}

	@Test
	public void theBoundOnPendingPostsStillHolds() throws Exception {
		group();
		ContactId stranger = node.addContact(strangerKey);
		node.manager.getRecentPosts(groupId);
		List<MessageId> ids = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			ids.add(node.receive(stranger, groupId, strangerKey, EPOCH,
					new byte[] {(byte) i}, node.now + i, 0L, SIG));
		}

		int kept = 0;
		for (MessageId id : ids) if (node.isStored(id)) kept++;
		assertTrue("a contact that is not a member kept " + kept
				+ " posts in the store", kept <= 125);
		assertTrue(node.isStored(ids.get(ids.size() - 1)));
	}

	@Test
	public void postsOfASenderBeyondWhatCanBeShownAreRemovedOnALoad()
			throws Exception {
		group();
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		List<MessageId> ids = new ArrayList<>();
		for (int i = 0; i < 230; i++) {
			ids.add(node.receive(a, groupId, aKey, EPOCH,
					new byte[] {(byte) i, (byte) (i >> 8)}, node.now + i, 0L,
					SIG));
		}
		node.dropFromMemory(groupId);

		assertEquals(200, countFrom(node.manager.getRecentPosts(groupId),
				aKey));

		int kept = 0;
		for (MessageId id : ids) if (node.isStored(id)) kept++;
		assertEquals("posts of one sender kept in the store beyond the"
				+ " newest the screen can show", 200, kept);
		for (int i = 30; i < 230; i++) {
			assertTrue(node.isStored(ids.get(i)));
		}
	}

	private void group() {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L));
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
