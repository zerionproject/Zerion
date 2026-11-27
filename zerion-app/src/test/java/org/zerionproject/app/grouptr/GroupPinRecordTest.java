package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupPinReceivedEvent;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DbException;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;

import javax.annotation.Nullable;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.grouptr.GroupTrTestNode.FORGED_SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupPinRecordTest {

	private static final long EPOCH = 5L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] newKey = key((byte) 4);
	private final byte[] postId = getRandomId();
	private final byte[] otherPostId = getRandomId();

	@Test
	public void thePinNeedsVersion15() {
		assertEquals(15, MessagingManager.PIN_MIN_VERSION);
		assertTrue(MessagingManager.MINOR_VERSION
				>= MessagingManager.PIN_MIN_VERSION);
		assertEquals(59, MessageTypes.GROUP_PIN);
	}

	@Test
	public void theCreatorSendsThePinToMembersOnVersion15AndCountsTheRest()
			throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId current = node.addContact(aKey);
		ContactId earlier = node.addContact(localKey);
		node.announceMinorVersion(earlier, 14);

		int skipped = node.manager.pinPost(groupId, postId);

		assertEquals(1, skipped);
		assertEquals(1, node.sent.size());
		assertEquals(current, node.sent.get(0).to);
		BdfList record = node.sent.get(0).body;
		assertEquals(5, record.size());
		assertEquals(59L, (long) record.getLong(0));
		assertArrayEquals(groupId, record.getRaw(1));
		assertArrayEquals(postId, record.getRaw(2));
		assertEquals(node.now, (long) record.getLong(3));
		assertArrayEquals(SIG, record.getRaw(4));
		assertArrayEquals(postId, node.manager.getPinnedPostId(groupId));
		assertTrue(node.manager.canPin(groupId));
	}

	@Test
	public void pinningAnotherPostReplacesTheFirstAndUnpinSendsNoPostId()
			throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId current = node.addContact(aKey);
		node.manager.pinPost(groupId, postId);
		node.sent.clear();

		node.manager.pinPost(groupId, otherPostId);
		assertArrayEquals(otherPostId, node.manager.getPinnedPostId(groupId));
		assertEquals(1, node.sent.size());
		assertArrayEquals(otherPostId, node.sent.get(0).body.getRaw(2));
		long pinnedAt = node.sent.get(0).body.getLong(3);
		node.sent.clear();

		assertEquals(0, node.manager.unpinPost(groupId));
		assertNull(node.manager.getPinnedPostId(groupId));
		assertEquals(1, node.sent.size());
		assertEquals(current, node.sent.get(0).to);
		BdfList record = node.sent.get(0).body;
		assertEquals(59L, (long) record.getLong(0));
		assertNull(record.getOptionalRaw(2));
		assertTrue("every record carries a later time",
				record.getLong(3) > pinnedAt);
	}

	@Test
	public void aMemberAppliesTheCreatorsPinAndNothingElse() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId fromMember = node.addContact(aKey);
		assertFalse(node.manager.canPin(groupId));

		pin(node, fromMember, postId, 10L, SIG);
		assertNull(node.manager.getPinnedPostId(groupId));

		pin(node, fromCreator, postId, 10L, FORGED_SIG);
		assertNull(node.manager.getPinnedPostId(groupId));

		pin(node, fromCreator, postId, 10L, SIG);
		assertArrayEquals(postId, node.manager.getPinnedPostId(groupId));

		pin(node, fromCreator, otherPostId, 9L, SIG);
		pin(node, fromCreator, otherPostId, 10L, SIG);
		assertArrayEquals(postId, node.manager.getPinnedPostId(groupId));

		pin(node, fromCreator, otherPostId, 11L, SIG);
		assertArrayEquals(otherPostId, node.manager.getPinnedPostId(groupId));

		pin(node, fromCreator, null, 12L, SIG);
		assertNull(node.manager.getPinnedPostId(groupId));

		pin(node, fromCreator, postId, 12L, SIG);
		assertNull("an unpin keeps its place in the order",
				node.manager.getPinnedPostId(groupId));
	}

	@Test
	public void aMemberAddedLaterReceivesThePin() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(aKey, 1L));
		node.addContact(aKey);
		ContactId added = node.addContact(newKey);
		node.manager.pinPost(groupId, postId);
		node.sent.clear();

		node.manager.addMember(groupId, newKey, "New");

		BdfList record = pinRecordSentTo(node, added);
		assertNotNull("no pin record", record);
		assertArrayEquals(groupId, record.getRaw(1));
		assertArrayEquals(postId, record.getRaw(2));
	}

	@Test
	public void aMemberThatUpdatesLaterReceivesThePin() throws Exception {
		GroupTrTestNode node = creatorDevice();
		node.addContact(aKey);
		ContactId earlier = node.addContact(localKey);
		node.announceMinorVersion(earlier, 14);
		assertEquals(1, node.manager.pinPost(groupId, postId));
		long pinnedAt = node.sent.get(0).body.getLong(3);
		node.sent.clear();

		node.versionUpdated(earlier, 14);
		assertNull(pinRecordSentTo(node, earlier));

		node.versionUpdated(earlier, 15);
		BdfList record = pinRecordSentTo(node, earlier);
		assertNotNull("no pin record", record);
		assertArrayEquals(postId, record.getRaw(2));
		assertEquals("re-signed over the same time", pinnedAt,
				(long) record.getLong(3));
	}

	@Test
	public void thePinIsWrittenInsideTheSendTransaction() throws Exception {
		GroupTrTestNode node = creatorDevice();
		node.addContact(aKey);
		node.addContact(localKey);
		int before = node.untransactedWrites.size();
		node.manager.pinPost(groupId, postId);
		assertEquals(node.untransactedWrites.toString(), before,
				node.untransactedWrites.size());
		assertArrayEquals(postId, node.manager.getPinnedPostId(groupId));
		node.manager.unpinPost(groupId);
		assertEquals(before, node.untransactedWrites.size());
		assertNull(node.manager.getPinnedPostId(groupId));
	}

	@Test
	public void aPinWhoseSendFailsLeavesNoPin() throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId failing = node.addContact(aKey);
		node.addContact(localKey);
		node.failCopiesTo(failing);
		try {
			node.manager.pinPost(groupId, postId);
			fail();
		} catch (DbException expected) {
		}
		assertNull("the creator shows a pin the members never got",
				node.manager.getPinnedPostId(groupId));
		assertTrue(node.sent.isEmpty());
	}

	@Test
	public void deletingThePinnedPostOnTheCreatorMovesTheUnpinForward()
			throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId fromMember = node.addContact(aKey);
		ContactId later = node.addContact(localKey);
		node.announceMinorVersion(later, 14);
		node.receive(fromMember, groupId, aKey, EPOCH,
				"hello".getBytes(StandardCharsets.UTF_8), 1_000L, 0L, SIG);
		byte[] stored = node.manager.getRecentPosts(groupId).get(0).getPostId();
		assertNotNull(stored);
		node.manager.pinPost(groupId, stored);
		long pinnedAt = node.sent.get(0).body.getLong(3);
		node.sent.clear();

		node.manager.deletePostsForMe(groupId,
				Collections.singletonList(stored));
		assertNull(node.manager.getPinnedPostId(groupId));

		node.versionUpdated(later, 15);
		BdfList record = pinRecordSentTo(node, later);
		assertNotNull("no unpin record", record);
		assertNull(record.getOptionalRaw(2));
		assertTrue("the unpin must be newer than the pin it replaces",
				record.getLong(3) > pinnedAt);
	}

	@Test
	public void deletingThePinnedPostClearsThePin() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		node.receive(fromCreator, groupId, creatorKey, EPOCH,
				"hello".getBytes(StandardCharsets.UTF_8), 1_000L, 0L, SIG);
		byte[] stored = node.manager.getRecentPosts(groupId).get(0).getPostId();
		assertNotNull(stored);
		pin(node, fromCreator, stored, 10L, SIG);
		assertArrayEquals(stored, node.manager.getPinnedPostId(groupId));

		node.manager.deletePostsForMe(groupId,
				Collections.singletonList(stored));

		assertNull(node.manager.getPinnedPostId(groupId));
	}

	@Test
	public void onlyTheCreatorMayPin() throws Exception {
		GroupTrTestNode node = memberDevice();
		node.addContact(creatorKey);
		try {
			node.manager.pinPost(groupId, postId);
			fail();
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.NOT_CREATOR,
					e.getReason());
		}
		try {
			node.manager.unpinPost(groupId);
			fail();
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.NOT_CREATOR,
					e.getReason());
		}
		assertTrue(GroupTrManagerImpl.mayPin(node.group(groupId), creatorKey));
		assertFalse(GroupTrManagerImpl.mayPin(node.group(groupId), localKey));
		assertFalse(GroupTrManagerImpl.mayPin(node.group(groupId), aKey));
		assertFalse(GroupTrManagerImpl.mayPin(null, creatorKey));
		assertFalse(GroupTrManagerImpl.mayPin(node.group(groupId), null));
	}

	@Test
	public void theSignedInputIsByteExact() {
		byte[] gid = new byte[32];
		Arrays.fill(gid, (byte) 0xAB);
		byte[] target = new byte[32];
		Arrays.fill(target, (byte) 0xCD);
		byte[] pinned = GroupTrManagerImpl.groupPinSignedInput(gid, target,
				0x0102030405060708L);
		assertEquals(74, pinned.length);
		assertArrayEquals(gid, Arrays.copyOfRange(pinned, 0, 32));
		assertEquals(1, pinned[32]);
		assertArrayEquals(target, Arrays.copyOfRange(pinned, 33, 65));
		assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8},
				Arrays.copyOfRange(pinned, 65, 73));
		assertEquals(0x0C, pinned[73]);

		byte[] unpinned = GroupTrManagerImpl.groupPinSignedInput(gid, null,
				0x0102030405060708L);
		assertEquals(74, unpinned.length);
		assertEquals(0, unpinned[32]);
		assertArrayEquals(new byte[32], Arrays.copyOfRange(unpinned, 33, 65));
		assertEquals(0x0C, unpinned[73]);
		assertFalse(Arrays.equals(pinned, unpinned));
	}

	@Nullable
	private BdfList pinRecordSentTo(GroupTrTestNode node, ContactId to)
			throws Exception {
		BdfList record = null;
		for (GroupTrTestNode.Sent s : node.sent) {
			if (s.to.equals(to) && s.body.getLong(0) == 59L) record = s.body;
		}
		return record;
	}

	private void pin(GroupTrTestNode node, ContactId from,
			@Nullable byte[] target, long timestamp, byte[] sig) {
		node.manager.eventOccurred(new GroupPinReceivedEvent(from, groupId,
				target, timestamp, sig));
	}

	private GroupTrTestNode creatorDevice() {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(aKey, 1L), member(localKey, 1L));
		return node;
	}

	private GroupTrTestNode memberDevice() {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L));
		return node;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
