package org.zerionproject.app.grouptr;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupLatePostsTest {

	private static final long EPOCH = 7L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] lateJoinerKey = key((byte) 4);
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);

	@Test
	public void aPostFromBehindByAMemberOfThatEpochIsShown() throws Exception {
		group();
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);

		MessageId id = node.receive(a, groupId, aKey, EPOCH - 2,
				"while you were away".getBytes("UTF-8"), node.now, 0L, SIG);

		assertEquals("a post from two epochs behind by a member of that"
				+ " epoch was lost", 1,
				countFrom(node.manager.getRecentPosts(groupId), aKey));
		assertTrue(node.isStored(id));
	}

	@Test
	public void postsThatArriveAfterTheCreatorsRecordsAreNotLost()
			throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH - 2, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L));
		ContactId a = node.addContact(aKey);
		ContactId fromCreator = node.addContact(creatorKey);
		node.manager.getRecentPosts(groupId);
		node.added(fromCreator, groupId, lateJoinerKey, EPOCH - 1);
		node.added(fromCreator, groupId, key((byte) 5), EPOCH);
		assertEquals(EPOCH, node.group(groupId).getEpoch());

		node.receive(a, groupId, aKey, EPOCH - 2, "one".getBytes("UTF-8"),
				node.now, 0L, SIG);
		node.receive(a, groupId, aKey, EPOCH - 2, "two".getBytes("UTF-8"),
				node.now + 1, 0L, SIG);

		assertEquals("posts sent before two additions were lost because the"
				+ " additions arrived first", 2,
				countFrom(node.manager.getRecentPosts(groupId), aKey));
	}

	@Test
	public void aPostFromBeforeItsSignerJoinedIsHeldNotRemoved()
			throws Exception {
		group();
		ContactId late = node.addContact(lateJoinerKey);
		node.manager.getRecentPosts(groupId);

		MessageId id = node.receive(late, groupId, lateJoinerKey, EPOCH - 3,
				"from an earlier membership".getBytes("UTF-8"), node.now, 0L,
				SIG);

		assertEquals(0, countFrom(node.manager.getRecentPosts(groupId),
				lateJoinerKey));
		assertTrue("a post refused for its epoch alone was removed",
				node.isStored(id));
		node.dropFromMemory(groupId);
		assertEquals(0, countFrom(node.manager.getRecentPosts(groupId),
				lateJoinerKey));
		assertTrue(node.isStored(id));
	}

	@Test
	public void aReplayOfAnOldPostIsStillCaughtByTheSeenSet()
			throws Exception {
		group();
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		node.receive(a, groupId, aKey, EPOCH - 2, "once".getBytes("UTF-8"),
				node.now, 0L, SIG);

		MessageId replay = node.receive(a, groupId, aKey, EPOCH - 2,
				"once".getBytes("UTF-8"), node.now, 0L, SIG);

		assertEquals(1, countFrom(node.manager.getRecentPosts(groupId), aKey));
		assertFalse(node.isStored(replay));
	}

	@Test
	public void theCreatorWasAMemberAtEveryEpoch() {
		assertTrue(GroupTrManagerImpl.signerWasMemberAt(node(), creatorKey,
				0L));
		assertTrue(GroupTrManagerImpl.signerWasMemberAt(node(), aKey, 1L));
		assertFalse(GroupTrManagerImpl.signerWasMemberAt(node(), aKey, 0L));
		assertFalse(GroupTrManagerImpl.signerWasMemberAt(node(),
				lateJoinerKey, 3L));
		assertTrue(GroupTrManagerImpl.signerWasMemberAt(node(),
				lateJoinerKey, 4L));
		assertFalse(GroupTrManagerImpl.signerWasMemberAt(node(),
				key((byte) 9), 9L));
	}

	private org.zerionproject.app.api.grouptr.GroupTrState node() {
		java.util.List<org.zerionproject.app.api.grouptr.GroupTrMember>
				members = new java.util.ArrayList<>();
		members.add(member(aKey, 1L));
		members.add(member(lateJoinerKey, 4L));
		return new org.zerionproject.app.api.grouptr.GroupTrState(groupId,
				"g", new byte[32], creatorKey, "Creator", 0L, EPOCH, false,
				members);
	}

	private void group() {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L),
				member(lateJoinerKey, EPOCH - 1));
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
