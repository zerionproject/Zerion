package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.core.api.contact.ContactId;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.canonical;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupSnapshotOwnJoinTest {

	private static final long JOIN_EPOCH = 2L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] earlierKey = key((byte) 2);
	private final byte[] joinerKey = key((byte) 3);

	@Test
	public void aNewMemberLearnsTheEarlierMembers() throws Exception {
		GroupTrTestNode joiner = freshlyJoined();
		ContactId fromCreator = joiner.addContact(creatorKey);

		joiner.added(fromCreator, groupId, joinerKey, JOIN_EPOCH);
		joiner.snapshot(fromCreator, groupId, JOIN_EPOCH, fullList());

		assertTrue("the third member must learn the second",
				joiner.isMember(groupId, earlierKey));
		assertEquals(JOIN_EPOCH, joiner.group(groupId).getEpoch());
	}

	@Test
	public void theJoinSnapshotAppliesOnlyOnce() throws Exception {
		GroupTrTestNode joiner = freshlyJoined();
		ContactId fromCreator = joiner.addContact(creatorKey);
		ContactId fromEarlier = joiner.addContact(earlierKey);

		joiner.added(fromCreator, groupId, joinerKey, JOIN_EPOCH);
		joiner.snapshot(fromCreator, groupId, JOIN_EPOCH, fullList());
		joiner.left(fromEarlier, groupId, earlierKey, JOIN_EPOCH + 1);
		joiner.snapshot(fromEarlier, groupId, JOIN_EPOCH, fullList());

		assertTrue(joiner.isMember(groupId, joinerKey));
		assertFalse("a replayed snapshot must not bring back a member that left",
				joiner.isMember(groupId, earlierKey));
	}

	@Test
	public void anEarlierMemberIgnoresASnapshotAtItsEpoch() throws Exception {
		GroupTrTestNode earlier = new GroupTrTestNode(earlierKey);
		earlier.putGroup(groupId, creatorKey, JOIN_EPOCH, creator(creatorKey),
				member(earlierKey, 1L), member(joinerKey, JOIN_EPOCH));
		ContactId fromCreator = earlier.addContact(creatorKey);

		earlier.snapshot(fromCreator, groupId, JOIN_EPOCH, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				earlierKey, MemberRole.MEMBER, 1));

		assertTrue(earlier.isMember(groupId, joinerKey));
	}

	private GroupTrTestNode freshlyJoined() {
		GroupTrTestNode joiner = new GroupTrTestNode(joinerKey);
		joiner.putGroup(groupId, creatorKey, 0L, creator(creatorKey),
				member(joinerKey, 0L));
		return joiner;
	}

	private byte[] fullList() {
		return canonical(creatorKey, MemberRole.CREATOR, 0,
				earlierKey, MemberRole.MEMBER, 1,
				joinerKey, MemberRole.MEMBER, (int) JOIN_EPOCH);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
