package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupPostDeliveryAcceptanceTest {

	private final byte[] creator = fill((byte) 1);
	private final byte[] member = fill((byte) 2);
	private final byte[] removed = fill((byte) 3);
	private final byte[] stranger = fill((byte) 4);

	private final byte[] other = fill((byte) 5);

	private GroupTrState group(boolean dissolved) {
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(member, "Member", 0L, 1L));
		return new GroupTrState(fill((byte) 9), "g", fill((byte) 8), creator,
				"Creator", 0L, 5L, dissolved, members);
	}

	private GroupTrState keyedGroup() {
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(member, "Member", 0L, 1L));
		members.add(new GroupTrMember(other, "Other", 0L, 1L));
		return new GroupTrState(fill((byte) 9), "g", fill((byte) 8), creator,
				"Creator", 0L, 5L, false, members, 0L,
				GroupTrState.SCHEME_GROUP_KEYS);
	}

	@Test
	public void withMemberKeysOnlyTheSignerOrTheCreatorMayDeliver() {
		assertTrue(GroupTrManagerImpl.groupPostDeliveryAccepted(
				keyedGroup(), member, member));
		assertTrue("the creator passes a member's record on",
				GroupTrManagerImpl.groupPostDeliveryAccepted(
						keyedGroup(), member, creator));
		assertFalse("another member may not pass a record on",
				GroupTrManagerImpl.groupPostDeliveryAccepted(
						keyedGroup(), member, other));
		assertFalse(GroupTrManagerImpl.groupPostDeliveryAccepted(
				keyedGroup(), stranger, creator));
		assertFalse(GroupTrManagerImpl.groupPostDeliveryAccepted(
				keyedGroup(), member, stranger));
	}

	@Test
	public void withAccountKeysAnyMemberStillDelivers() {
		GroupTrState s = group(false);
		List<GroupTrMember> members = new ArrayList<>(s.getMembers());
		members.add(new GroupTrMember(other, "Other", 0L, 1L));
		s.setMembers(members);
		assertTrue(GroupTrManagerImpl.groupPostDeliveryAccepted(s, member,
				other));
		assertTrue(GroupTrManagerImpl.groupPostDeliveryAccepted(s, member,
				creator));
	}

	@Test
	public void memberSignerDeliveredByMemberIsAccepted() {
		assertTrue(GroupTrManagerImpl.groupPostDeliveryAccepted(
				group(false), member, creator));
		assertTrue(GroupTrManagerImpl.groupPostDeliveryAccepted(
				group(false), creator, member));
	}

	@Test
	public void removedMemberDeliveringAMemberPostIsRejected() {
		assertFalse("a removed member must not resurrect a member's post",
				GroupTrManagerImpl.groupPostDeliveryAccepted(
						group(false), member, removed));
	}

	@Test
	public void nonMemberRelayIsRejected() {
		assertFalse(GroupTrManagerImpl.groupPostDeliveryAccepted(
				group(false), member, stranger));
	}

	@Test
	public void nonMemberSignerIsRejected() {
		assertFalse(GroupTrManagerImpl.groupPostDeliveryAccepted(
				group(false), removed, member));
	}

	@Test
	public void dissolvedGroupAcceptsNothing() {
		assertFalse(GroupTrManagerImpl.groupPostDeliveryAccepted(
				group(true), member, creator));
	}

	@Test
	public void nullGroupAcceptsNothing() {
		assertFalse(GroupTrManagerImpl.groupPostDeliveryAccepted(
				null, member, creator));
	}

	private static byte[] fill(byte b) {
		byte[] a = new byte[32];
		Arrays.fill(a, b);
		return a;
	}
}
