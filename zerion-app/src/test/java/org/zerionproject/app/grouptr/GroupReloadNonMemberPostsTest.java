package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.contact.ContactId;
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

public class GroupReloadNonMemberPostsTest {

	private static final long EPOCH = 5L;

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] bKey = key((byte) 4);
	private final byte[] strangerKey = key((byte) 5);
	private final byte[] groupId = getRandomId();
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);
	private long clock = 1_000L;

	@Test
	public void aLoadReadsNoPostThatANonMemberDelivered() throws Exception {
		ContactId a = node.addContact(aKey);
		ContactId stranger = node.addContact(strangerKey);
		open();
		List<MessageId> strangerPosts = new ArrayList<>();
		byte[] body = new byte[64 * 1024];
		for (int i = 0; i < 300; i++) {
			strangerPosts.add(node.store(stranger, groupId, strangerKey, EPOCH,
					body, clock++, 0L));
		}
		for (int i = 0; i < 5; i++) {
			node.storeAndPost(a, groupId, aKey, EPOCH, new byte[16], clock++);
		}

		for (int load = 0; load < 3; load++) {
			node.dropFromMemory(groupId);
			node.forgetReads();
			List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);
			int read = 0;
			for (MessageId id : strangerPosts) {
				if (node.wasRead(id)) read++;
			}
			assertEquals(5, loaded.size());
			assertEquals("posts of the non-member read by load " + load,
					0, read);
			assertTrue("load " + load + " read " + node.bodyBytesRead()
							+ " bytes of post bodies",
					node.bodyBytesRead() <= 2 * 5 * 16);
		}
	}

	@Test
	public void ownPostsKeptWithAFormerMemberStillShowAfterALoad()
			throws Exception {
		ContactId b = node.addContact(bKey);
		open();
		List<MessageId> fromB = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			fromB.add(node.storeAndPost(b, groupId, bKey, EPOCH, new byte[16],
					clock++));
		}
		for (int i = 0; i < 3; i++) {
			node.now++;
			node.manager.sendGroupPost(groupId, ("own " + i).getBytes("UTF-8"),
					0L);
		}
		ContactId fromCreator = node.addContact(creatorKey);
		node.removed(fromCreator, groupId, bKey, EPOCH);
		node.commit(fromCreator, groupId, EPOCH);
		assertFalse(node.isMember(groupId, bKey));
		node.dropFromMemory(groupId);
		node.forgetReads();

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals("own posts shown after a load", 3,
				countFrom(loaded, localKey));
		assertEquals(0, countFrom(loaded, bKey));
		for (MessageId id : fromB) {
			assertFalse("a post the former member delivered was read",
					node.wasRead(id));
		}
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
