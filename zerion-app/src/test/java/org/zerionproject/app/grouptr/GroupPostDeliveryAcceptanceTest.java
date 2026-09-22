package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * PROTO-07: a relayed group post is delivered only when both its signer and
 * the contact that delivered it are current members, so a removed member or a
 * non-member relay cannot resurrect an old signed post.
 */
public class GroupPostDeliveryAcceptanceTest {

	private final byte[] creator = fill((byte) 1);
	private final byte[] member = fill((byte) 2);
	private final byte[] removed = fill((byte) 3);
	private final byte[] stranger = fill((byte) 4);

	private GroupTrState group(boolean dissolved) {
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(member, "Member", 0L, 1L));
		return new GroupTrState(fill((byte) 9), "g", fill((byte) 8), creator,
				"Creator", 0L, 5L, dissolved, members);
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
