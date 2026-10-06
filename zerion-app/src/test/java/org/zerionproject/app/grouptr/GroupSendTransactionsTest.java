package org.zerionproject.app.grouptr;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupSendTransactionsTest {

	private static final long EPOCH = 4L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);

	@Test
	public void eachCopyOfAPostIsStoredInItsOwnTransaction()
			throws Exception {
		byte[] a = key((byte) 3);
		byte[] b = key((byte) 4);
		byte[] c = key((byte) 5);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L), member(b, 1L),
				member(c, 1L));
		node.addContact(a);
		node.addContact(b);
		node.addContact(c);

		node.manager.sendGroupPost(groupId, getRandomBytes(1_000_000), 0L);

		assertEquals(3, node.sent.size());
		assertEquals("copies of the post stored under one write lock", 1,
				node.maxCopiesPerWriteTxn());
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
