package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupLargePostTest {

	private static final long EPOCH = 4L;
	private static final int LARGE = 1024 * 1024;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] currentKey = key((byte) 3);
	private final byte[] earlierKey = key((byte) 4);
	private final byte[] strangerKey = key((byte) 5);
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);

	@Test
	public void aLargePostIsNotSentToAMemberOnAnEarlierVersion()
			throws Exception {
		group();
		ContactId current = node.addContact(currentKey);
		ContactId earlier = node.addContact(earlierKey);
		node.announceMinorVersion(earlier, 7);

		int skipped = node.manager.sendGroupPost(groupId,
				getRandomBytes(LARGE), 0L);

		assertEquals(1, skipped);
		assertEquals(1, node.sent.size());
		assertEquals(current, node.sent.get(0).to);
	}

	@Test
	public void aPostWithinTheEarlierLimitReachesEveryMember()
			throws Exception {
		group();
		node.addContact(currentKey);
		ContactId earlier = node.addContact(earlierKey);
		node.announceMinorVersion(earlier, 7);

		int skipped = node.manager.sendGroupPost(groupId,
				getRandomBytes(64 * 1024), 0L);

		assertEquals(0, skipped);
		assertEquals(2, node.sent.size());
	}

	@Test
	public void aLargeOwnPostIsKeptOutOfTheMetadataAndReadBack()
			throws Exception {
		group();
		node.addContact(currentKey);
		byte[] body = getRandomBytes(LARGE);

		node.manager.sendGroupPost(groupId, body, 0L);
		node.dropFromMemory(groupId);
		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals(1, loaded.size());
		assertArrayEquals(body, loaded.get(0).getBody());
		MessageId copy = node.storedIds().get(0);
		BdfDictionary meta = node.metadata(copy);
		assertNotNull(meta);
		assertNull(meta.get("groupCiphertext"));
		assertEquals((long) LARGE, (long) meta.getLong("groupBodyLength"));
	}

	@Test
	public void aStoredPostOfAnEarlierVersionIsConvertedOnce()
			throws Exception {
		group();
		ContactId current = node.addContact(currentKey);
		byte[] body = getRandomBytes(1000);
		MessageId id = node.store(current, groupId, currentKey, EPOCH, body,
				node.now, 0L);

		node.manager.onDatabaseOpened(
				new org.zerionproject.core.api.db.Transaction(null, false));

		BdfDictionary meta = node.metadata(id);
		assertNotNull(meta);
		assertNull(meta.get("groupCiphertext"));
		assertEquals(1000L, (long) meta.getLong("groupBodyLength"));
		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);
		assertEquals(1, loaded.size());
		assertArrayEquals(body, loaded.get(0).getBody());
	}

	@Test
	public void membersThatAreNotContactsAreListedAsOutOfReach()
			throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(currentKey, 1L),
				member(strangerKey, 1L));
		node.addContact(creatorKey);
		node.addContact(currentKey);

		List<GroupTrMember> out = node.manager.getMembersOutOfReach(groupId);

		assertEquals(1, out.size());
		assertArrayEquals(strangerKey, out.get(0).getPubKey());
		boolean selfListed = false;
		for (GroupTrMember m : out) {
			if (Arrays.equals(m.getPubKey(), localKey)) selfListed = true;
		}
		assertFalse(selfListed);
		assertTrue(node.manager.getMembersOutOfReach(getRandomId())
				.isEmpty());
	}

	private void group() {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(currentKey, 1L),
				member(earlierKey, 1L));
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
