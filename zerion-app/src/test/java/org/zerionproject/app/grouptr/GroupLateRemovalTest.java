package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.settings.Settings;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrReceiveOrder.KEY_COMMITS;
import static org.zerionproject.app.grouptr.GroupTrReceiveOrder.KEY_DEPARTED;
import static org.zerionproject.app.grouptr.GroupTrTestNode.canonical;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupLateRemovalTest {

	private static final long EPOCH = 5L;
	private static final long RELAYED = EPOCH + 4;
	private static final long ADDED_AGAIN = EPOCH + 3;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] removedKey = key((byte) 3);
	private final byte[] otherKey = key((byte) 4);
	private final byte[] newKey = key((byte) 5);

	@Test
	public void aCommitAloneChangesNothing() throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);

		node.commit(fromCreator, groupId, EPOCH);

		assertEquals("an epoch commit must not apply without its removal",
				EPOCH, node.group(groupId).getEpoch());
		assertTrue(node.isMember(groupId, removedKey));
		assertNull(node.namespace(GroupTrReceiveOrder.namespace(groupId)));
	}

	@Test
	public void theRemovalAfterItsCommitApplies() throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);

		node.commit(fromCreator, groupId, EPOCH);
		node.removed(fromCreator, groupId, removedKey, EPOCH);

		assertFalse(node.isMember(groupId, removedKey));
		assertTrue(node.isMember(groupId, otherKey));
		assertEquals(EPOCH + 1, node.group(groupId).getEpoch());
	}

	@Test
	public void aRemovalArrivingAfterTheCreatorMovedOnStillApplies()
			throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);

		node.commit(fromCreator, groupId, EPOCH);
		node.added(fromCreator, groupId, newKey, EPOCH + 2);
		node.removed(fromCreator, groupId, removedKey, EPOCH);
		node.removed(fromCreator, groupId, removedKey, EPOCH);

		assertFalse(node.isMember(groupId, removedKey));
		assertTrue(node.isMember(groupId, newKey));
		assertTrue(node.isMember(groupId, otherKey));
		assertEquals("a late removal never moves the epoch", EPOCH + 2,
				node.group(groupId).getEpoch());
	}

	@Test
	public void aLateRemovalRelayedByAMemberIsRefused() throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId fromOther = node.addContact(otherKey);

		node.added(fromCreator, groupId, newKey, EPOCH + 2);
		node.removed(fromOther, groupId, removedKey, EPOCH);

		assertTrue(node.isMember(groupId, removedKey));
	}

	@Test
	public void aCreatorRemovalAfterARelayedRoleChangeStillApplies()
			throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId fromOther = node.addContact(otherKey);

		node.roleChanged(fromOther, groupId, otherKey, MemberRole.ADMIN,
				EPOCH + 2);
		node.removed(fromCreator, groupId, removedKey, EPOCH);
		node.commit(fromCreator, groupId, EPOCH);

		assertFalse(node.isMember(groupId, removedKey));
		assertEquals(EPOCH + 2, node.group(groupId).getEpoch());
	}

	@Test
	public void twoRemovalsAndAnAdditionInAnyOrderRemoveBoth()
			throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);

		node.commit(fromCreator, groupId, EPOCH);
		node.commit(fromCreator, groupId, EPOCH + 1);
		node.added(fromCreator, groupId, newKey, EPOCH + 3);
		node.removed(fromCreator, groupId, removedKey, EPOCH);
		node.removed(fromCreator, groupId, otherKey, EPOCH + 1);

		assertFalse(node.isMember(groupId, removedKey));
		assertFalse(node.isMember(groupId, otherKey));
		assertTrue(node.isMember(groupId, newKey));
		assertEquals(EPOCH + 3, node.group(groupId).getEpoch());
	}

	@Test
	public void aLateRemovalDoesNotRemoveAMemberAddedAgain()
			throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);

		node.added(fromCreator, groupId, removedKey, EPOCH + 2);
		node.removed(fromCreator, groupId, removedKey, EPOCH);

		assertTrue(node.isMember(groupId, removedKey));
		assertEquals(EPOCH + 2, node.group(groupId).getEpoch());
	}

	@Test
	public void anOlderAdditionMarksTheMemberAddedAgain() throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId fromOther = node.addContact(otherKey);

		node.roleChanged(fromOther, groupId, otherKey, MemberRole.ADMIN,
				RELAYED);
		node.added(fromCreator, groupId, removedKey, ADDED_AGAIN);
		node.removed(fromCreator, groupId, removedKey, EPOCH);

		assertTrue("the creator added the member again after that removal",
				node.isMember(groupId, removedKey));
		assertEquals(ADDED_AGAIN, entry(node, removedKey).getJoinedAtEpoch());
		assertEquals("an older addition never moves the epoch", RELAYED,
				node.group(groupId).getEpoch());
	}

	@Test
	public void anOlderAdditionClearsTheDepartureButAddsNobody()
			throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId fromOther = node.addContact(otherKey);

		node.roleChanged(fromOther, groupId, otherKey, MemberRole.ADMIN,
				RELAYED);
		node.removed(fromCreator, groupId, removedKey, EPOCH);
		node.added(fromCreator, groupId, removedKey, ADDED_AGAIN);

		assertFalse("an older addition does not add an absent member",
				node.isMember(groupId, removedKey));
		assertFalse(departed(node).contains(toHexString(removedKey)));
		assertEquals(RELAYED, node.group(groupId).getEpoch());
		node.snapshot(fromCreator, groupId, RELAYED, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				localKey, MemberRole.MEMBER, 1,
				removedKey, MemberRole.MEMBER, (int) ADDED_AGAIN,
				otherKey, MemberRole.ADMIN, 3));
		assertTrue(node.isMember(groupId, removedKey));
	}

	@Test
	public void anOlderAdditionNeverLowersAnEntry() throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);

		node.added(fromCreator, groupId, otherKey, 1L);

		assertEquals(3L, entry(node, otherKey).getJoinedAtEpoch());
		assertEquals(EPOCH, node.group(groupId).getEpoch());
	}

	@Test
	public void anOlderAdditionFromAMemberChangesNothing() throws Exception {
		GroupTrTestNode node = receiver();
		node.addContact(creatorKey);
		ContactId fromOther = node.addContact(otherKey);

		node.added(fromOther, groupId, removedKey, EPOCH);

		assertEquals(2L, entry(node, removedKey).getJoinedAtEpoch());
	}

	@Test
	public void aLateRemovalNeverRemovesTheDeviceItself() throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);

		node.added(fromCreator, groupId, newKey, EPOCH + 2);
		node.removed(fromCreator, groupId, localKey, EPOCH);

		assertTrue(node.isMember(groupId, localKey));
		assertEquals(EPOCH + 2, node.group(groupId).getEpoch());
	}

	@Test
	public void aLateRemovalIsWrittenWithTheStateInOneTransaction()
			throws Exception {
		GroupTrTestNode node = receiver();
		ContactId fromCreator = node.addContact(creatorKey);

		node.added(fromCreator, groupId, newKey, EPOCH + 2);
		node.untransactedWrites.clear();
		node.removed(fromCreator, groupId, removedKey, EPOCH);

		assertFalse(node.isMember(groupId, removedKey));
		assertTrue("written outside the state's transaction: "
				+ node.untransactedWrites, node.untransactedWrites.isEmpty());
	}

	@Test
	public void commitsHeldByAnEarlierBuildAreDeletedAtStart()
			throws Exception {
		GroupTrTestNode node = receiver();
		String ns = GroupTrReceiveOrder.namespace(groupId);
		node.putSetting(ns, KEY_COMMITS, EPOCH + "." + (EPOCH + 1) + "."
				+ (node.now + 1_000_000L));
		node.putSetting(ns, KEY_DEPARTED, toHexString(newKey) + ".3");

		node.restart();

		assertNull(node.namespace(ns).get(KEY_COMMITS));
		assertTrue(departed(node).contains(toHexString(newKey)));
	}

	private java.util.Set<String> departed(GroupTrTestNode node) {
		java.util.Set<String> out = new java.util.HashSet<>();
		Settings s = node.namespace(GroupTrReceiveOrder.namespace(groupId));
		if (s == null) return out;
		String stored = s.get(KEY_DEPARTED);
		if (stored == null || stored.isEmpty()) return out;
		for (String e : stored.split(",")) out.add(e.split("\\.")[0]);
		return out;
	}

	private GroupTrMember entry(GroupTrTestNode node, byte[] pub)
			throws Exception {
		for (GroupTrMember m : node.group(groupId).getMembers()) {
			if (Arrays.equals(m.getPubKey(), pub)) return m;
		}
		throw new AssertionError("not a member");
	}

	private GroupTrTestNode receiver() {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(removedKey, 2L),
				member(otherKey, 3L));
		return node;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
