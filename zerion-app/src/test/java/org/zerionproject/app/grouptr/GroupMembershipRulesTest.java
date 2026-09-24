package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.messaging.event.GroupMembershipChangedEvent.ChangeKind;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The membership state machine's admission rules, checked before any
 * signature: who may sign each kind of record, which records must advance
 * the epoch, that the creator can neither be removed nor reported as leaving,
 * that an epoch commit must continue exactly from the current epoch, and the
 * shape bounds of a member list snapshot. Every rule is exercised with a
 * replayed or stale epoch, a non-creator sender and a dissolved group.
 */
public class GroupMembershipRulesTest {

	private static final long EPOCH = 5L;

	private final byte[] creator = fill((byte) 1);
	private final byte[] member = fill((byte) 2);
	private final byte[] stranger = fill((byte) 4);

	@Test
	public void onlyTheCreatorAddsMembers() {
		GroupTrState s = group(false);
		assertArrayEquals(creator, GroupTrManagerImpl.membershipEventSigner(s,
				ChangeKind.MEMBER_ADDED, creator, stranger, EPOCH + 1, 0L));
		assertNull(GroupTrManagerImpl.membershipEventSigner(s,
				ChangeKind.MEMBER_ADDED, member, stranger, EPOCH + 1, 0L));
		assertNull(GroupTrManagerImpl.membershipEventSigner(s,
				ChangeKind.MEMBER_ADDED, null, stranger, EPOCH + 1, 0L));
		assertNull(GroupTrManagerImpl.membershipEventSigner(group(true),
				ChangeKind.MEMBER_ADDED, creator, stranger, EPOCH + 1, 0L));
	}

	@Test
	public void removalsNeedTheCreatorAFreshEpochAndNeverTargetTheCreator() {
		GroupTrState s = group(false);
		assertArrayEquals(creator, GroupTrManagerImpl.membershipEventSigner(s,
				ChangeKind.MEMBER_REMOVED, creator, member, EPOCH + 1,
				EPOCH + 1));
		assertNull("replayed epoch", GroupTrManagerImpl.membershipEventSigner(
				s, ChangeKind.MEMBER_REMOVED, creator, member, EPOCH, EPOCH));
		assertNull("stale epoch", GroupTrManagerImpl.membershipEventSigner(
				s, ChangeKind.MEMBER_REMOVED, creator, member, EPOCH - 1,
				EPOCH - 1));
		assertNull("member as sender", GroupTrManagerImpl.membershipEventSigner(
				s, ChangeKind.MEMBER_REMOVED, member, member, EPOCH + 1,
				EPOCH + 1));
		assertNull("unknown sender", GroupTrManagerImpl.membershipEventSigner(
				s, ChangeKind.MEMBER_REMOVED, null, member, EPOCH + 1,
				EPOCH + 1));
		assertNull("creator as target", GroupTrManagerImpl.membershipEventSigner(
				s, ChangeKind.MEMBER_REMOVED, creator, creator, EPOCH + 1,
				EPOCH + 1));
		assertNull("no target", GroupTrManagerImpl.membershipEventSigner(
				s, ChangeKind.MEMBER_REMOVED, creator, null, EPOCH + 1,
				EPOCH + 1));
		assertNull("dissolved", GroupTrManagerImpl.membershipEventSigner(
				group(true), ChangeKind.MEMBER_REMOVED, creator, member,
				EPOCH + 1, EPOCH + 1));
	}

	@Test
	public void aMemberSignsItsOwnLeavingAndTheCreatorNeverLeaves() {
		GroupTrState s = group(false);
		assertArrayEquals(member, GroupTrManagerImpl.membershipEventSigner(s,
				ChangeKind.MEMBER_LEFT, stranger, member, EPOCH + 1, 0L));
		assertArrayEquals(member, GroupTrManagerImpl.membershipEventSigner(s,
				ChangeKind.MEMBER_LEFT, null, member, EPOCH + 1, 0L));
		assertNull(GroupTrManagerImpl.membershipEventSigner(s,
				ChangeKind.MEMBER_LEFT, creator, creator, EPOCH + 1, 0L));
		assertNull(GroupTrManagerImpl.membershipEventSigner(s,
				ChangeKind.MEMBER_LEFT, member, null, EPOCH + 1, 0L));
		assertNull(GroupTrManagerImpl.membershipEventSigner(group(true),
				ChangeKind.MEMBER_LEFT, member, member, EPOCH + 1, 0L));
	}

	@Test
	public void dissolutionIsTheCreatorsAndMustAdvanceTheEpoch() {
		GroupTrState s = group(false);
		assertArrayEquals(creator, GroupTrManagerImpl.membershipEventSigner(s,
				ChangeKind.GROUP_DISSOLVED, member, null, EPOCH + 1, 0L));
		assertNull(GroupTrManagerImpl.membershipEventSigner(s,
				ChangeKind.GROUP_DISSOLVED, creator, null, EPOCH, 0L));
		assertNull(GroupTrManagerImpl.membershipEventSigner(s,
				ChangeKind.GROUP_DISSOLVED, creator, null, 0L, 0L));
		assertNull(GroupTrManagerImpl.membershipEventSigner(group(true),
				ChangeKind.GROUP_DISSOLVED, creator, null, EPOCH + 1, 0L));
	}

	@Test
	public void roleChangesAreSignedByTheCreator() {
		GroupTrState s = group(false);
		assertArrayEquals(creator, GroupTrManagerImpl.membershipEventSigner(s,
				ChangeKind.ROLE_CHANGED, member, member, EPOCH + 1, 0L));
		assertNull(GroupTrManagerImpl.membershipEventSigner(group(true),
				ChangeKind.ROLE_CHANGED, creator, member, EPOCH + 1, 0L));
	}

	@Test
	public void anEpochCommitContinuesExactlyFromTheCurrentEpoch() {
		GroupTrState s = group(false);
		assertTrue(GroupTrManagerImpl.epochCommitAccepted(s, EPOCH, EPOCH + 1,
				creator));
		assertFalse("replay", GroupTrManagerImpl.epochCommitAccepted(s,
				EPOCH - 1, EPOCH, creator));
		assertFalse("skip", GroupTrManagerImpl.epochCommitAccepted(s, EPOCH,
				EPOCH + 2, creator));
		assertFalse("from the future", GroupTrManagerImpl.epochCommitAccepted(
				s, EPOCH + 1, EPOCH + 2, creator));
		assertFalse("backwards", GroupTrManagerImpl.epochCommitAccepted(s,
				EPOCH, EPOCH, creator));
		assertFalse("member", GroupTrManagerImpl.epochCommitAccepted(s, EPOCH,
				EPOCH + 1, member));
		assertFalse("unknown", GroupTrManagerImpl.epochCommitAccepted(s, EPOCH,
				EPOCH + 1, null));
		assertFalse("dissolved", GroupTrManagerImpl.epochCommitAccepted(
				group(true), EPOCH, EPOCH + 1, creator));
	}

	@Test
	public void aSnapshotMustAdvanceTheEpochAndFitTheMemberBound() {
		GroupTrState s = group(false);
		assertTrue(GroupTrManagerImpl.snapshotShapeAccepted(s, EPOCH + 1, 0));
		assertTrue(GroupTrManagerImpl.snapshotShapeAccepted(s, EPOCH + 1, 37));
		assertTrue(GroupTrManagerImpl.snapshotShapeAccepted(s, EPOCH + 1,
				37 * GroupTrConstants.MAX_GROUP_MEMBERS));
		assertFalse(GroupTrManagerImpl.snapshotShapeAccepted(s, EPOCH + 1,
				37 * (GroupTrConstants.MAX_GROUP_MEMBERS + 1)));
		assertFalse(GroupTrManagerImpl.snapshotShapeAccepted(s, EPOCH + 1, 36));
		assertFalse(GroupTrManagerImpl.snapshotShapeAccepted(s, EPOCH + 1, 38));
		assertFalse(GroupTrManagerImpl.snapshotShapeAccepted(s, EPOCH, 37));
		assertFalse(GroupTrManagerImpl.snapshotShapeAccepted(s, EPOCH - 3, 37));
		assertFalse(GroupTrManagerImpl.snapshotShapeAccepted(group(true),
				EPOCH + 1, 37));
	}

	private GroupTrState group(boolean dissolved) {
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(member, "Member", 0L, 1L));
		return new GroupTrState(fill((byte) 9), "g", fill((byte) 8), creator,
				"Creator", 0L, EPOCH, dissolved, members);
	}

	private static byte[] fill(byte v) {
		byte[] b = new byte[32];
		Arrays.fill(b, v);
		return b;
	}
}
