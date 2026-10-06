package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.messaging.event.GroupMembershipChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupMembershipChangedEvent.ChangeKind;
import org.zerionproject.core.api.contact.ContactId;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIGNED;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupLeaveReplayTest {

	private static final long EPOCH = 5L;
	private static final long MINUTE = 60_000L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] mKey = key((byte) 3);

	@Test
	public void anAdditionOfAListedMemberMovesItsEntry() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(mKey, 2L));
		ContactId fromCreator = node.addContact(creatorKey);
		node.addContact(mKey);

		node.added(fromCreator, groupId, mKey, EPOCH + 4);

		GroupTrMember m = find(node, mKey);
		assertEquals("the entry of a member added again kept its old join",
				EPOCH + 4, m.getJoinedAtEpoch());
		assertEquals(node.now, m.getJoinedAt());
		assertEquals(EPOCH + 4, node.group(groupId).getEpoch());
	}

	@Test
	public void anOldLeavingReplayedAfterTheNewAdditionIsRefused()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(mKey, 2L));
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId fromM = node.addContact(mKey);
		node.added(fromCreator, groupId, mKey, EPOCH + 4);

		node.left(fromM, groupId, mKey, EPOCH + 1);

		assertTrue("a leaving signed before the member was added again took"
				+ " it out", node.isMember(groupId, mKey));
	}

	@Test
	public void aLeavingDatedBeforeTheMemberJoinedIsRefused()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L),
				new GroupTrMember(mKey, "M", node.now, 2L));
		node.addContact(creatorKey);
		ContactId fromM = node.addContact(mKey);

		left(node, fromM, EPOCH + 1, node.now - 10 * MINUTE);

		assertTrue("a leaving dated ten minutes before the member joined took"
				+ " it out", node.isMember(groupId, mKey));
	}

	@Test
	public void aLeavingFromAClockSlightlyBehindIsTaken() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L),
				new GroupTrMember(mKey, "M", node.now, 2L));
		node.addContact(creatorKey);
		ContactId fromM = node.addContact(mKey);

		left(node, fromM, EPOCH + 1, node.now - MINUTE);

		assertFalse(node.isMember(groupId, mKey));
	}

	private void left(GroupTrTestNode node, ContactId from, long epoch,
			long timestamp) {
		node.manager.eventOccurred(new GroupMembershipChangedEvent(from,
				ChangeKind.MEMBER_LEFT, groupId, epoch, timestamp, mKey, null,
				0L, 0L, SIG, SIGNED));
	}

	private GroupTrMember find(GroupTrTestNode node, byte[] pub)
			throws Exception {
		for (GroupTrMember m : node.group(groupId).getMembers()) {
			if (Arrays.equals(m.getPubKey(), pub)) return m;
		}
		throw new AssertionError("not a member");
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
