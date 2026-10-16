package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.settings.Settings;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrReceiveOrder.KEY_DEPARTED;
import static org.zerionproject.app.grouptr.GroupTrTestNode.canonical;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.util.StringUtils.toHexString;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupDepartureTest {

	private static final long EPOCH = 5L;
	private static final int EARLIER_VERSION = 7;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] leaverKey = key((byte) 3);
	private final byte[] otherKey = key((byte) 4);
	private final byte[] newKey = key((byte) 5);

	@Test
	public void aSnapshotRelayedByAMemberAtTheCurrentEpochAddsMembers()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, EPOCH), member(otherKey, 1L));
		node.addContact(creatorKey);
		ContactId fromOther = node.addContact(otherKey);

		node.snapshot(fromOther, groupId, EPOCH, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				otherKey, MemberRole.MEMBER, 1,
				leaverKey, MemberRole.MEMBER, 2,
				localKey, MemberRole.MEMBER, (int) EPOCH));

		assertTrue("the creator's snapshot counts whoever relays it",
				node.isMember(groupId, leaverKey));
		assertEquals(EPOCH, node.group(groupId).getEpoch());
	}

	@Test
	public void aLeaverStaysOutOfTheCreatorsNextSnapshot() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId fromLeaver = node.addContact(leaverKey);

		node.left(fromLeaver, groupId, leaverKey, EPOCH + 1);
		node.snapshot(fromCreator, groupId, EPOCH + 1, fullList(
				newKey, EPOCH + 1));

		assertFalse("a snapshot the creator made before it took the leaving"
				+ " must not bring the member back",
				node.isMember(groupId, leaverKey));
		assertTrue(node.isMember(groupId, newKey));
		assertEquals(EPOCH + 1, node.group(groupId).getEpoch());
	}

	@Test
	public void aLeaverAddedAgainByTheCreatorIsListedAgain()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId fromLeaver = node.addContact(leaverKey);

		node.left(fromLeaver, groupId, leaverKey, EPOCH + 1);
		assertTrue(departed(node).containsKey(toHexString(leaverKey)));
		node.added(fromCreator, groupId, leaverKey, EPOCH + 2);

		assertTrue(node.isMember(groupId, leaverKey));
		assertFalse("an addition clears the departure",
				departed(node).containsKey(toHexString(leaverKey)));
		node.snapshot(fromCreator, groupId, EPOCH + 3, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				localKey, MemberRole.MEMBER, 1,
				leaverKey, MemberRole.MEMBER, (int) EPOCH + 2,
				otherKey, MemberRole.MEMBER, 3));
		assertTrue(node.isMember(groupId, leaverKey));
	}

	@Test
	public void anOldLeavingRelayedLateIsRecordedAtItsOwnEpoch()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId fromOther = node.addContact(otherKey);

		node.roleChanged(fromOther, groupId, otherKey, MemberRole.ADMIN,
				EPOCH + 4);
		node.left(fromOther, groupId, leaverKey, EPOCH + 1);
		assertFalse(node.isMember(groupId, leaverKey));
		node.snapshot(fromCreator, groupId, EPOCH + 4, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				localKey, MemberRole.MEMBER, 1,
				leaverKey, MemberRole.MEMBER, (int) EPOCH + 3,
				otherKey, MemberRole.ADMIN, 3));

		assertTrue("the creator added the member again after it left",
				node.isMember(groupId, leaverKey));
	}

	@Test
	public void aLeavingInTheGroupOfAnOlderCreatorIsRemembered()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		node.announceMinorVersion(fromCreator, EARLIER_VERSION);
		ContactId fromLeaver = node.addContact(leaverKey);

		node.left(fromLeaver, groupId, leaverKey, EPOCH + 1);
		assertEquals(EPOCH + 1, node.group(groupId).getEpoch());
		node.snapshot(fromCreator, groupId, EPOCH + 1, fullList(
				newKey, EPOCH + 1));

		assertFalse(node.isMember(groupId, leaverKey));
		assertTrue(node.isMember(groupId, newKey));
	}

	@Test
	public void anInviteeOutsideTheCreatorsContactsKeepsItsEpochOnALeaving()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.putSetting(GroupTrConstants.SETTINGS_NS_PREFIX
						+ toHexString(groupId),
				GroupTrMemberInvites.S_JOINED_THROUGH_MEMBER, "true");
		ContactId fromOther = node.addContact(otherKey);
		ContactId fromLeaver = node.addContact(leaverKey);

		node.left(fromLeaver, groupId, leaverKey, EPOCH + 1);

		assertFalse(node.isMember(groupId, leaverKey));
		assertEquals("a member's own leaving must not move the epoch of an"
				+ " invitee whose creator runs version 10 or later", EPOCH,
				node.group(groupId).getEpoch());
		node.snapshot(fromOther, groupId, EPOCH, fullList(otherKey, 3L));
		assertFalse("the passed on snapshot must not bring the member back",
				node.isMember(groupId, leaverKey));
	}

	@Test
	public void aLateRemovalIsRememberedFromItsOwnStep() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);

		node.commit(fromCreator, groupId, EPOCH);
		node.added(fromCreator, groupId, newKey, EPOCH + 3);
		node.removed(fromCreator, groupId, leaverKey, EPOCH);
		assertFalse(node.isMember(groupId, leaverKey));

		node.snapshot(fromCreator, groupId, EPOCH + 3, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				localKey, MemberRole.MEMBER, 1,
				leaverKey, MemberRole.MEMBER, 2,
				otherKey, MemberRole.MEMBER, 3,
				newKey, MemberRole.MEMBER, (int) EPOCH + 3));
		assertFalse("the removed membership stays out",
				node.isMember(groupId, leaverKey));

		node.snapshot(fromCreator, groupId, EPOCH + 3, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				localKey, MemberRole.MEMBER, 1,
				leaverKey, MemberRole.MEMBER, (int) EPOCH + 2,
				otherKey, MemberRole.MEMBER, 3,
				newKey, MemberRole.MEMBER, (int) EPOCH + 3));
		assertTrue("the creator added the member again after the removal",
				node.isMember(groupId, leaverKey));
	}

	@Test
	public void aRemovalAtTheCurrentEpochIsRemembered() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);

		node.removed(fromCreator, groupId, leaverKey, EPOCH - 1);
		assertFalse(node.isMember(groupId, leaverKey));
		assertEquals(EPOCH, node.group(groupId).getEpoch());

		node.snapshot(fromCreator, groupId, EPOCH, fullList(otherKey, 3L));
		assertFalse(node.isMember(groupId, leaverKey));
	}

	private byte[] fullList(byte[] extra, long extraJoinedAtEpoch) {
		return canonical(creatorKey, MemberRole.CREATOR, 0,
				localKey, MemberRole.MEMBER, 1,
				leaverKey, MemberRole.MEMBER, 2,
				otherKey, MemberRole.MEMBER, 3,
				extra, MemberRole.MEMBER, (int) extraJoinedAtEpoch);
	}

	private java.util.Map<String, String> departed(GroupTrTestNode node) {
		java.util.Map<String, String> out = new java.util.HashMap<>();
		Settings s = node.namespace(GroupTrReceiveOrder.namespace(groupId));
		if (s == null) return out;
		String stored = s.get(KEY_DEPARTED);
		if (stored == null || stored.isEmpty()) return out;
		for (String entry : stored.split(",")) {
			String[] parts = entry.split("\\.");
			out.put(parts[0], parts[1]);
		}
		return out;
	}

	private GroupTrTestNode memberDevice() {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(leaverKey, 2L),
				member(otherKey, 3L));
		return node;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
