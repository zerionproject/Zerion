package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.core.api.contact.ContactId;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.FORGED_SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.canonical;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupEqualEpochSnapshotTest {

	private static final long JOIN_EPOCH = 2L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] earlierKey = key((byte) 2);
	private final byte[] joinerKey = key((byte) 3);
	private final byte[] laterKey = key((byte) 4);
	private final byte[] extraKey = key((byte) 5);

	@Test
	public void aNewMemberTakesPostsFromAMemberItLearnedOfAtItsOwnEpoch()
			throws Exception {
		GroupTrTestNode joiner = freshlyJoined();
		ContactId fromCreator = joiner.addContact(creatorKey);
		ContactId fromEarlier = joiner.addContact(earlierKey);

		joiner.added(fromCreator, groupId, joinerKey, JOIN_EPOCH);
		joiner.snapshot(fromCreator, groupId, JOIN_EPOCH, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				earlierKey, MemberRole.MEMBER, 1,
				joinerKey, MemberRole.MEMBER, (int) JOIN_EPOCH));
		joiner.receive(fromEarlier, groupId, earlierKey, JOIN_EPOCH,
				bytes("hello"), joiner.now, 0L, SIG);

		assertTrue(joiner.isMember(groupId, earlierKey));
		assertEquals(1, countFrom(joiner.manager.getRecentPosts(groupId),
				earlierKey));
		assertEquals(JOIN_EPOCH, joiner.group(groupId).getEpoch());
	}

	@Test
	public void aNewMemberAddedJustBeforeAnotherStillLearnsEveryone()
			throws Exception {
		GroupTrTestNode joiner = freshlyJoined();
		ContactId fromCreator = joiner.addContact(creatorKey);
		ContactId fromEarlier = joiner.addContact(earlierKey);

		joiner.added(fromCreator, groupId, joinerKey, JOIN_EPOCH);
		joiner.added(fromCreator, groupId, laterKey, JOIN_EPOCH + 1);
		joiner.snapshot(fromCreator, groupId, JOIN_EPOCH, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				earlierKey, MemberRole.MEMBER, 1,
				joinerKey, MemberRole.MEMBER, (int) JOIN_EPOCH));
		joiner.snapshot(fromCreator, groupId, JOIN_EPOCH + 1, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				earlierKey, MemberRole.MEMBER, 1,
				joinerKey, MemberRole.MEMBER, (int) JOIN_EPOCH,
				laterKey, MemberRole.MEMBER, (int) JOIN_EPOCH + 1));
		joiner.receive(fromEarlier, groupId, earlierKey, JOIN_EPOCH + 1,
				bytes("hello"), joiner.now, 0L, SIG);

		assertTrue("the first new member must learn the earlier member from"
				+ " the snapshot at its current epoch",
				joiner.isMember(groupId, earlierKey));
		assertEquals(1, countFrom(joiner.manager.getRecentPosts(groupId),
				earlierKey));
		assertEquals(JOIN_EPOCH + 1, joiner.group(groupId).getEpoch());
	}

	@Test
	public void aSnapshotAtTheCurrentEpochRemovesNobody() throws Exception {
		GroupTrTestNode joiner = new GroupTrTestNode(joinerKey);
		joiner.putGroup(groupId, creatorKey, JOIN_EPOCH, creator(creatorKey),
				member(joinerKey, 0L), member(extraKey, JOIN_EPOCH));
		ContactId fromCreator = joiner.addContact(creatorKey);

		joiner.snapshot(fromCreator, groupId, JOIN_EPOCH, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				earlierKey, MemberRole.MEMBER, 1,
				joinerKey, MemberRole.MEMBER, (int) JOIN_EPOCH));

		assertTrue(joiner.isMember(groupId, earlierKey));
		assertTrue("only a snapshot at a higher epoch may take a member out",
				joiner.isMember(groupId, extraKey));
		assertEquals(JOIN_EPOCH, joiner.group(groupId).getEpoch());
	}

	@Test
	public void aSnapshotAtTheCurrentEpochChangesNoListedMember()
			throws Exception {
		GroupTrTestNode joiner = new GroupTrTestNode(joinerKey);
		joiner.putGroup(groupId, creatorKey, JOIN_EPOCH, creator(creatorKey),
				member(joinerKey, 0L), member(earlierKey, 1L));
		ContactId fromCreator = joiner.addContact(creatorKey);

		joiner.snapshot(fromCreator, groupId, JOIN_EPOCH, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				earlierKey, MemberRole.ADMIN, 1,
				joinerKey, MemberRole.MEMBER, (int) JOIN_EPOCH,
				laterKey, MemberRole.MEMBER, (int) JOIN_EPOCH));

		assertTrue(joiner.isMember(groupId, laterKey));
		GroupTrMember earlier = entry(joiner, earlierKey);
		assertEquals("a role changes only with a newer record",
				MemberRole.MEMBER, earlier.getRole());
		assertEquals(1L, earlier.getJoinedAtEpoch());
		assertEquals(0L, entry(joiner, joinerKey).getJoinedAtEpoch());
		assertEquals(JOIN_EPOCH, joiner.group(groupId).getEpoch());
	}

	@Test
	public void aSnapshotAtTheCurrentEpochNeedsTheCreatorsSignature()
			throws Exception {
		GroupTrTestNode joiner = freshlyJoined();
		ContactId fromCreator = joiner.addContact(creatorKey);

		joiner.added(fromCreator, groupId, joinerKey, JOIN_EPOCH);
		joiner.snapshot(fromCreator, groupId, JOIN_EPOCH, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				earlierKey, MemberRole.MEMBER, 1,
				joinerKey, MemberRole.MEMBER, (int) JOIN_EPOCH), FORGED_SIG);

		assertFalse(joiner.isMember(groupId, earlierKey));
	}

	@Test
	public void aSnapshotAtTheCurrentEpochNeverRemovesTheDeviceItself()
			throws Exception {
		GroupTrTestNode joiner = new GroupTrTestNode(joinerKey);
		joiner.putGroup(groupId, creatorKey, JOIN_EPOCH, creator(creatorKey),
				member(joinerKey, JOIN_EPOCH));
		ContactId fromCreator = joiner.addContact(creatorKey);

		joiner.snapshot(fromCreator, groupId, JOIN_EPOCH, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				earlierKey, MemberRole.MEMBER, 1));

		assertNotNull(joiner.manager.getGroup(groupId));
		assertTrue(joiner.isMember(groupId, joinerKey));
		assertFalse("a snapshot that does not list this device changes"
				+ " nothing at the current epoch",
				joiner.isMember(groupId, earlierKey));
	}

	@Test
	public void theCreatorTakesNoMemberFromASnapshotAtItsEpoch()
			throws Exception {
		GroupTrTestNode owner = new GroupTrTestNode(creatorKey);
		owner.putGroup(groupId, creatorKey, JOIN_EPOCH, creator(creatorKey),
				member(earlierKey, 1L));
		ContactId fromEarlier = owner.addContact(earlierKey);

		owner.snapshot(fromEarlier, groupId, JOIN_EPOCH, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				earlierKey, MemberRole.MEMBER, 1,
				extraKey, MemberRole.MEMBER, (int) JOIN_EPOCH));

		assertFalse(owner.isMember(groupId, extraKey));
	}

	@Test
	public void aMemberThatLeftAtThisEpochStaysOutAfterARestart()
			throws Exception {
		GroupTrTestNode joiner = freshlyJoined();
		ContactId fromCreator = joiner.addContact(creatorKey);
		ContactId fromEarlier = joiner.addContact(earlierKey);
		byte[] full = canonical(creatorKey, MemberRole.CREATOR, 0,
				earlierKey, MemberRole.MEMBER, 1,
				joinerKey, MemberRole.MEMBER, (int) JOIN_EPOCH);

		joiner.added(fromCreator, groupId, joinerKey, JOIN_EPOCH);
		joiner.snapshot(fromCreator, groupId, JOIN_EPOCH, full);
		joiner.left(fromEarlier, groupId, earlierKey, JOIN_EPOCH + 1);
		assertEquals(JOIN_EPOCH, joiner.group(groupId).getEpoch());
		joiner.restart();
		joiner.snapshot(fromCreator, groupId, JOIN_EPOCH, full);

		assertFalse("a snapshot from before the leaving must not bring the"
				+ " member back", joiner.isMember(groupId, earlierKey));
		assertTrue(joiner.isMember(groupId, joinerKey));
	}

	@Test
	public void aMemberThatLeftCanBeAddedAgainAtALaterEpoch()
			throws Exception {
		GroupTrTestNode joiner = freshlyJoined();
		ContactId fromCreator = joiner.addContact(creatorKey);
		ContactId fromEarlier = joiner.addContact(earlierKey);

		joiner.added(fromCreator, groupId, joinerKey, JOIN_EPOCH);
		joiner.snapshot(fromCreator, groupId, JOIN_EPOCH, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				earlierKey, MemberRole.MEMBER, 1,
				joinerKey, MemberRole.MEMBER, (int) JOIN_EPOCH));
		joiner.left(fromEarlier, groupId, earlierKey, JOIN_EPOCH + 1);
		joiner.removed(fromCreator, groupId, earlierKey, JOIN_EPOCH);
		joiner.commit(fromCreator, groupId, JOIN_EPOCH);
		joiner.added(fromCreator, groupId, laterKey, JOIN_EPOCH + 2);
		joiner.snapshot(fromCreator, groupId, JOIN_EPOCH + 2, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				earlierKey, MemberRole.MEMBER, (int) JOIN_EPOCH + 2,
				joinerKey, MemberRole.MEMBER, (int) JOIN_EPOCH,
				laterKey, MemberRole.MEMBER, (int) JOIN_EPOCH + 2));

		assertTrue(joiner.isMember(groupId, laterKey));
		assertTrue("an equal-epoch snapshot may add a member that left at an"
				+ " earlier epoch", joiner.isMember(groupId, earlierKey));
	}

	private GroupTrTestNode freshlyJoined() {
		GroupTrTestNode joiner = new GroupTrTestNode(joinerKey);
		joiner.putGroup(groupId, creatorKey, 0L, creator(creatorKey),
				member(joinerKey, 0L));
		return joiner;
	}

	private GroupTrMember entry(GroupTrTestNode node, byte[] pub)
			throws Exception {
		for (GroupTrMember m : node.group(groupId).getMembers()) {
			if (Arrays.equals(m.getPubKey(), pub)) return m;
		}
		throw new AssertionError("not a member");
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
