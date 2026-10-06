package org.zerionproject.app.grouptr;

import org.zerionproject.core.api.contact.ContactId;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupRemovalOrderTest {

	private static final long EPOCH = 5L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] removedKey = key((byte) 3);
	private final byte[] otherKey = key((byte) 4);

	@Test
	public void removalThenCommitRemovesTheMember() throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);

		node.removed(fromCreator, groupId, removedKey, EPOCH);
		node.commit(fromCreator, groupId, EPOCH);

		assertFalse(node.isMember(groupId, removedKey));
		assertEquals(EPOCH + 1, node.group(groupId).getEpoch());
	}

	@Test
	public void commitThenRemovalRemovesTheMember() throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);

		node.commit(fromCreator, groupId, EPOCH);
		node.removed(fromCreator, groupId, removedKey, EPOCH);

		assertFalse("the removal must not be lost when its commit came first",
				node.isMember(groupId, removedKey));
		assertEquals(EPOCH + 1, node.group(groupId).getEpoch());
	}

	@Test
	public void bothOrdersEndInTheSameState() throws Exception {
		GroupTrTestNode first = receiver();
		GroupTrTestNode second = receiver();
		ContactId creatorAtFirst = first.addContact(creatorKey);
		ContactId creatorAtSecond = second.addContact(creatorKey);

		first.removed(creatorAtFirst, groupId, removedKey, EPOCH);
		first.commit(creatorAtFirst, groupId, EPOCH);
		second.commit(creatorAtSecond, groupId, EPOCH);
		second.removed(creatorAtSecond, groupId, removedKey, EPOCH);

		assertEquals(first.group(groupId).getEpoch(),
				second.group(groupId).getEpoch());
		assertEquals(memberKeys(first), memberKeys(second));
		assertFalse(second.isMember(groupId, removedKey));
		assertTrue(second.isMember(groupId, otherKey));
	}

	@Test
	public void aRepeatedRemovalChangesNothing() throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);

		node.commit(fromCreator, groupId, EPOCH);
		node.removed(fromCreator, groupId, removedKey, EPOCH);
		node.removed(fromCreator, groupId, removedKey, EPOCH);

		assertFalse("the removed member stays out",
				node.isMember(groupId, removedKey));
		assertTrue("no other member is touched",
				node.isMember(groupId, otherKey));
		assertEquals(EPOCH + 1, node.group(groupId).getEpoch());
	}

	@Test
	public void aRemovalForAnEarlierEpochIsStillRefused() throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);

		node.commit(fromCreator, groupId, EPOCH);
		node.removed(fromCreator, groupId, otherKey, EPOCH - 1);

		assertTrue("a stale removal must not take effect",
				node.isMember(groupId, otherKey));
		assertEquals(EPOCH + 1, node.group(groupId).getEpoch());
	}

	@Test
	public void onlyTheCreatorRemovesAtTheCurrentEpoch() throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId fromOther = node.addContact(otherKey);

		node.commit(fromCreator, groupId, EPOCH);
		node.removed(fromOther, groupId, removedKey, EPOCH);

		assertTrue(node.isMember(groupId, removedKey));
	}

	private GroupTrTestNode receiver() {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(removedKey, 2L),
				member(otherKey, 3L));
		return node;
	}

	private String memberKeys(GroupTrTestNode node) throws Exception {
		StringBuilder sb = new StringBuilder();
		node.group(groupId).getMembers().forEach(m ->
				sb.append(Arrays.toString(m.getPubKey())).append(';'));
		return sb.toString();
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
