package org.zerionproject.app.grouptr;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfList;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupMemberLeftEpochTest {

	private static final long EPOCH = 5L;
	private static final int EARLIER_VERSION = 7;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] leaverKey = key((byte) 3);
	private final byte[] removedKey = key((byte) 4);
	private final byte[] newKey = key((byte) 5);
	private final byte[] colluderKey = key((byte) 6);
	private final byte[] secondColluderKey = key((byte) 7);

	@Test
	public void leavingDoesNotMoveTheEpoch() throws Exception {
		GroupTrTestNode node = memberDevice();
		node.addContact(creatorKey);
		ContactId fromLeaver = node.addContact(leaverKey);

		node.left(fromLeaver, groupId, leaverKey, EPOCH + 1);

		assertEquals("a member's own leaving must not move the shared epoch",
				EPOCH, node.group(groupId).getEpoch());
		assertFalse(node.isMember(groupId, leaverKey));
	}

	@Test
	public void theCreatorsRecordsStillApplyAfterALeaving()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId fromLeaver = node.addContact(leaverKey);

		node.left(fromLeaver, groupId, leaverKey, EPOCH + 1);
		node.removed(fromCreator, groupId, removedKey, EPOCH);
		node.commit(fromCreator, groupId, EPOCH);
		node.added(fromCreator, groupId, newKey, EPOCH + 2);

		assertFalse(node.isMember(groupId, leaverKey));
		assertFalse("the creator's next removal must apply",
				node.isMember(groupId, removedKey));
		assertTrue("the creator's next addition must apply",
				node.isMember(groupId, newKey));
		assertEquals(EPOCH + 2, node.group(groupId).getEpoch());
	}

	@Test
	public void leavingsSentToOneDeviceOnlyDoNotPutItAhead()
			throws Exception {
		GroupTrTestNode targeted = memberDevice();
		GroupTrTestNode other = memberDevice();
		ContactId creatorAtTargeted = targeted.addContact(creatorKey);
		ContactId creatorAtOther = other.addContact(creatorKey);
		ContactId leaverAtTargeted = targeted.addContact(leaverKey);
		ContactId colluderAtTargeted = targeted.addContact(colluderKey);
		ContactId secondAtTargeted = targeted.addContact(secondColluderKey);

		targeted.left(leaverAtTargeted, groupId, leaverKey, EPOCH + 1);
		targeted.left(colluderAtTargeted, groupId, colluderKey, EPOCH + 2);
		targeted.left(secondAtTargeted, groupId, secondColluderKey,
				EPOCH + 3);
		for (GroupTrTestNode n : Arrays.asList(targeted, other)) {
			ContactId c = n == targeted ? creatorAtTargeted : creatorAtOther;
			n.removed(c, groupId, removedKey, EPOCH);
			n.commit(c, groupId, EPOCH);
		}

		assertEquals(other.group(groupId).getEpoch(),
				targeted.group(groupId).getEpoch());
		assertFalse("the targeted device must still apply the removal",
				targeted.isMember(groupId, removedKey));
		assertFalse(other.isMember(groupId, removedKey));
	}

	@Test
	public void theCreatorConfirmsALeavingWithItsRemovalAndCommit()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(leaverKey, 2L));
		ContactId toMember = node.addContact(localKey);
		ContactId fromLeaver = node.addContact(leaverKey);

		node.left(fromLeaver, groupId, leaverKey, EPOCH + 1);

		assertFalse(node.isMember(groupId, leaverKey));
		assertEquals(EPOCH + 1, node.group(groupId).getEpoch());
		List<BdfList> toRemaining = sentTo(node, toMember);
		assertEquals("a removal and an epoch commit reach the remaining member",
				2, toRemaining.size());
		BdfList removal = find(toRemaining, 34L);
		assertArrayEquals(leaverKey, removal.getRaw(2));
		assertEquals(EPOCH, (long) removal.getLong(3));
		assertEquals(EPOCH + 1, (long) removal.getLong(4));
		BdfList commit = find(toRemaining, 37L);
		assertEquals(EPOCH, (long) commit.getLong(2));
		assertEquals(EPOCH + 1, (long) commit.getLong(3));
		assertTrue("nothing is sent to the member that left",
				sentTo(node, fromLeaver).isEmpty());
	}

	@Test
	public void aRepeatedLeavingIsConfirmedOnce() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(leaverKey, 2L));
		node.addContact(localKey);
		ContactId fromLeaver = node.addContact(leaverKey);

		node.left(fromLeaver, groupId, leaverKey, EPOCH + 1);
		node.left(fromLeaver, groupId, leaverKey, EPOCH + 1);
		node.left(fromLeaver, groupId, leaverKey, EPOCH + 2);

		assertEquals(2, node.sent.size());
		assertEquals(EPOCH + 1, node.group(groupId).getEpoch());
	}

	@Test
	public void theCreatorConfirmsALeavingSignedAtAStaleEpoch()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(leaverKey, 2L));
		node.addContact(localKey);
		ContactId fromLeaver = node.addContact(leaverKey);

		node.left(fromLeaver, groupId, leaverKey, EPOCH - 1);

		assertFalse("a member that left while behind must still be taken"
				+ " out, or it keeps receiving posts", node.isMember(groupId,
				leaverKey));
		assertEquals(EPOCH + 1, node.group(groupId).getEpoch());
	}

	@Test
	public void aLeavingFromAnEarlierMembershipIsIgnored() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(leaverKey, EPOCH));
		node.addContact(creatorKey);
		ContactId fromLeaver = node.addContact(leaverKey);

		node.left(fromLeaver, groupId, leaverKey, EPOCH);

		assertTrue("a leaving signed before the member was added again is"
				+ " a replay", node.isMember(groupId, leaverKey));
	}

	@Test
	public void aLeavingInTheGroupOfACreatorOnAnEarlierVersionMovesTheEpoch()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		node.announceMinorVersion(fromCreator, EARLIER_VERSION);
		ContactId fromLeaver = node.addContact(leaverKey);

		node.left(fromLeaver, groupId, leaverKey, EPOCH + 1);

		assertFalse(node.isMember(groupId, leaverKey));
		assertEquals("the device stays in step with a creator that moves its"
				+ " own epoch on a leaving", EPOCH + 1,
				node.group(groupId).getEpoch());
		node.added(fromCreator, groupId, newKey, EPOCH + 2);
		assertTrue(node.isMember(groupId, newKey));
	}

	private GroupTrTestNode memberDevice() {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(leaverKey, 2L),
				member(removedKey, 3L), member(colluderKey, 3L),
				member(secondColluderKey, 3L));
		return node;
	}

	private static List<BdfList> sentTo(GroupTrTestNode node, ContactId to) {
		List<BdfList> out = new ArrayList<>();
		for (GroupTrTestNode.Sent s : node.sent) {
			if (s.to.equals(to)) out.add(s.body);
		}
		return out;
	}

	private static BdfList find(List<BdfList> records, long type)
			throws Exception {
		for (BdfList r : records) {
			if (r.getLong(0) == type) return r;
		}
		throw new AssertionError("no record of type " + type);
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
