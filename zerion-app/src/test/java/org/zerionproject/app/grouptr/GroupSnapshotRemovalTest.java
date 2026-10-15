package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.app.api.messaging.event.GroupTrSelfRemovedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.canonical;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupSnapshotRemovalTest {

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] inviterKey = key((byte) 2);
	private final byte[] localKey = key((byte) 3);
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);

	@Test
	public void aConfirmedMemberLeftOutOfANewerSnapshotRemovesTheGroup()
			throws Exception {
		node.putGroup(groupId, creatorKey, 3L, creator(creatorKey),
				member(inviterKey, 1L), member(localKey, 3L));
		ContactId fromInviter = node.addContact(inviterKey);

		node.snapshot(fromInviter, groupId, 4L, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				inviterKey, MemberRole.MEMBER, 1));

		assertNull(node.manager.getGroup(groupId));
		assertEquals(1, selfRemovals());
	}

	@Test
	public void aSnapshotThatIsNotNewerRemovesNothing() throws Exception {
		node.putGroup(groupId, creatorKey, 3L, creator(creatorKey),
				member(inviterKey, 1L), member(localKey, 3L));
		ContactId fromInviter = node.addContact(inviterKey);

		node.snapshot(fromInviter, groupId, 3L, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				inviterKey, MemberRole.MEMBER, 1));

		assertTrue(node.isMember(groupId, localKey));
		assertEquals(0, selfRemovals());
	}

	@Test
	public void aJoinStillWaitingIsNotEndedByASnapshotWithoutIt()
			throws Exception {
		node.putGroup(groupId, creatorKey, 0L, creator(creatorKey),
				member(inviterKey, 0L), member(localKey, 0L));
		node.putSetting(GroupTrConstants.SETTINGS_NS_PREFIX
						+ toHexString(groupId), GroupTrMemberInvites.S_JOINING_VIA,
				toHexString(inviterKey));
		ContactId fromInviter = node.addContact(inviterKey);

		node.snapshot(fromInviter, groupId, 2L, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				inviterKey, MemberRole.MEMBER, 1));

		assertNotNull(node.manager.getGroup(groupId));
		assertTrue(node.manager.isWaitingForAdmin(groupId));
		assertEquals(0, selfRemovals());
	}

	@Test
	public void theJoiningMarkEndsWhenTheCreatorListsTheInvitee()
			throws Exception {
		node.putGroup(groupId, creatorKey, 0L, creator(creatorKey),
				member(inviterKey, 0L), member(localKey, 0L));
		node.putSetting(GroupTrConstants.SETTINGS_NS_PREFIX
						+ toHexString(groupId), GroupTrMemberInvites.S_JOINING_VIA,
				toHexString(inviterKey));
		ContactId fromInviter = node.addContact(inviterKey);

		node.snapshot(fromInviter, groupId, 2L, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				inviterKey, MemberRole.MEMBER, 1,
				localKey, MemberRole.MEMBER, 2));
		assertFalse(node.manager.isWaitingForAdmin(groupId));
		assertNull(node.namespace(GroupTrConstants.SETTINGS_NS_PREFIX
				+ toHexString(groupId)).get(GroupTrMemberInvites.S_JOINING_VIA));

		node.snapshot(fromInviter, groupId, 3L, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				inviterKey, MemberRole.MEMBER, 1));

		assertNull("the removal reaches an invitee the creator cannot reach",
				node.manager.getGroup(groupId));
		assertEquals(1, selfRemovals());
	}

	private int selfRemovals() {
		int n = 0;
		for (Object o : node.broadcasts) {
			if (o instanceof GroupTrSelfRemovedEvent && Arrays.equals(
					((GroupTrSelfRemovedEvent) o).getGroupId(), groupId)) {
				n++;
			}
		}
		return n;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
