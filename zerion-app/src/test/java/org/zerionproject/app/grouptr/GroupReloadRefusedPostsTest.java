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

public class GroupReloadRefusedPostsTest {

	private static final long EPOCH = 5L;

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] bKey = key((byte) 4);
	private final byte[] xKey = key((byte) 7);
	private final byte[] groupId = getRandomId();
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);
	private long clock = 1_000L;

	@Test
	public void postsWithAClassicalSignatureAreNotReadAgain()
			throws Exception {
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		for (int i = 0; i < 10; i++) {
			node.store(b, groupId, bKey, EPOCH, new byte[16], clock++, 0L);
		}
		List<MessageId> refused = new ArrayList<>();
		for (int i = 0; i < 1000; i++) {
			refused.add(node.storeWithSig(a, groupId, aKey, EPOCH,
					new byte[1024], clock++, new byte[64]));
		}
		node.forgetReads();

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals(10, loaded.size());
		assertEquals(10, countFrom(loaded, bKey));
		assertEquals("refused posts read again after the first read",
				0, readAgain(refused));
		assertEquals("stored posts read again after the first read",
				10, node.metadataReads() - 1010);
	}

	@Test
	public void postsOfASenderWithNoKnownMlDsaKeyAreNotReadAgain()
			throws Exception {
		ContactId b = node.addContact(bKey);
		node.withoutMlDsaKey(xKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(bKey, 1L), member(xKey, 1L));
		for (int i = 0; i < 10; i++) {
			node.store(b, groupId, bKey, EPOCH, new byte[16], clock++, 0L);
		}
		List<MessageId> relayed = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			relayed.add(node.store(b, groupId, xKey, EPOCH, new byte[1024],
					clock++, 0L));
		}
		node.forgetReads();

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals(10, loaded.size());
		assertEquals(0, countFrom(loaded, xKey));
		assertEquals("posts that cannot verify read again after the first"
				+ " read", 0, readAgain(relayed));
	}

	private int readAgain(List<MessageId> ids) {
		int n = 0;
		for (MessageId id : ids) {
			if (node.timesRead(id) > 1) n++;
		}
		return n;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
