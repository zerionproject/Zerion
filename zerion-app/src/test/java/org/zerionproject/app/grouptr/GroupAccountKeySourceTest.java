package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.canonical;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupAccountKeySourceTest {

	private static final long EPOCH = 4L;

	private final byte[] groupId = getRandomId();
	private final byte[] otherGroupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] strangerKey = key((byte) 4);
	private final byte[] contactMlDsa = mlDsa((byte) 7);
	private final byte[] entryMlDsa = mlDsa((byte) 8);
	private final byte[] strangerMlDsa = mlDsa((byte) 9);
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);

	@Test
	public void aPostIsCheckedUnderTheContactRecordNotAMemberEntry()
			throws Exception {
		node.entryMlDsaKey(aKey, entryMlDsa);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L));
		ContactId a = node.addContact(aKey, contactMlDsa);
		node.manager.getRecentPosts(groupId);

		node.receive(a, groupId, aKey, EPOCH, "hello".getBytes("UTF-8"),
				node.now, 0L, SIG);

		assertFalse("the post was not checked",
				node.verifiedWithMlDsa.isEmpty());
		for (byte[] used : node.verifiedWithMlDsa) {
			assertArrayEquals("a member entry outranked the contact record",
					contactMlDsa, used);
		}
	}

	@Test
	public void aSnapshotTakesTheContactRecordOverADisagreeingEntry()
			throws Exception {
		node.entryMlDsaKey(aKey, entryMlDsa);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L));
		node.addContact(aKey, contactMlDsa);
		ContactId fromCreator = node.addContact(creatorKey);

		node.snapshot(fromCreator, groupId, EPOCH + 1, canonical(
				creatorKey, MemberRole.CREATOR, 0L,
				localKey, MemberRole.MEMBER, 1L,
				aKey, MemberRole.MEMBER, 1L));

		assertArrayEquals(contactMlDsa, entryOf(aKey).getMlDsaPubKey());
	}

	@Test
	public void aSnapshotNeverTakesAKeyFromAnotherGroupsEntry()
			throws Exception {
		node.entryMlDsaKey(strangerKey, strangerMlDsa);
		node.putGroup(otherGroupId, creatorKey, 1L, creator(creatorKey),
				member(localKey, 0L), member(strangerKey, 0L));
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L));
		ContactId fromCreator = node.addContact(creatorKey);

		node.snapshot(fromCreator, groupId, EPOCH + 1, canonical(
				creatorKey, MemberRole.CREATOR, 0L,
				localKey, MemberRole.MEMBER, 1L,
				strangerKey, MemberRole.MEMBER, EPOCH + 1));

		GroupTrMember stranger = entryOf(strangerKey);
		assertNull("a key from another group's entry was carried over",
				stranger.getMlDsaPubKey());
	}

	@Test
	public void joiningTakesTheCreatorsKeyOnlyFromTheContactRecord()
			throws Exception {
		node.entryMlDsaKey(creatorKey, entryMlDsa);
		node.putGroup(otherGroupId, creatorKey, 1L, creator(creatorKey),
				member(localKey, 0L));
		ContactId fromCreator = node.addContact(creatorKey, null);
		node.putPendingOffer(groupId, fromCreator, creatorKey, node.now);

		node.manager.acceptInvite(groupId);

		assertNull("the creator's key was taken from another group's entry",
				entryOf(creatorKey).getMlDsaPubKey());
	}

	private GroupTrMember entryOf(byte[] pub) throws Exception {
		for (GroupTrMember m : node.group(groupId).getMembers()) {
			if (Arrays.equals(m.getPubKey(), pub)) return m;
		}
		throw new AssertionError("not listed");
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}

	private static byte[] mlDsa(byte b) {
		byte[] k = new byte[PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES];
		Arrays.fill(k, b);
		return k;
	}
}
