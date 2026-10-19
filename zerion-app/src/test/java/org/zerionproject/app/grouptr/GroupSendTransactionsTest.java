package org.zerionproject.app.grouptr;

import org.junit.Test;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.grouptr.GroupTrPostSummary;
import org.zerionproject.app.api.grouptr.GroupTrSendResult;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupTrPostAcceptedEvent;
import org.zerionproject.core.api.db.DbException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupSendTransactionsTest {

	private static final long EPOCH = 4L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);

	@Test
	public void eachCopyOfAPostIsStoredInItsOwnTransaction()
			throws Exception {
		byte[] a = key((byte) 3);
		byte[] b = key((byte) 4);
		byte[] c = key((byte) 5);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L), member(b, 1L),
				member(c, 1L));
		node.addContact(a);
		node.addContact(b);
		node.addContact(c);

		node.manager.sendGroupPost(groupId, getRandomBytes(1_000_000), 0L);

		assertEquals(3, node.sent.size());
		assertEquals("copies of the post stored under one write lock", 1,
				node.maxCopiesPerWriteTxn());
	}

	@Test
	public void aCopyThatFailsForOneMemberDoesNotCancelThePost()
			throws Exception {
		byte[] a = key((byte) 3);
		byte[] b = key((byte) 4);
		byte[] c = key((byte) 5);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L), member(b, 1L),
				member(c, 1L));
		node.addContact(a);
		node.failCopiesTo(node.addContact(b));
		node.addContact(c);

		GroupTrSendResult result = node.manager.sendGroupPostReporting(
				groupId, "hello".getBytes(StandardCharsets.UTF_8), 0L);

		assertEquals(3, result.getRecipients());
		assertEquals(1, result.getFailed());
		assertEquals(2, result.getReached());
		assertEquals("the members reached got it", 2, node.sent.size());
		List<GroupTrPost> shown = node.manager.getRecentPosts(groupId);
		assertEquals("the post is kept, so retrying cannot repeat it", 1,
				shown.size());
		assertArrayEquals(result.getPostId(), shown.get(0).getPostId());
	}

	@Test
	public void aPostNoMemberGotIsNotKept() throws Exception {
		byte[] a = key((byte) 3);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		node.failCopiesTo(node.addContact(a));

		try {
			node.manager.sendGroupPostReporting(groupId,
					"hello".getBytes(StandardCharsets.UTF_8), 0L);
			fail("reported as sent while nobody got it");
		} catch (DbException expected) {
		}

		assertTrue(node.manager.getRecentPosts(groupId).isEmpty());
	}

	@Test
	public void aPostThatReachedSomeMembersIsSummarisedWithItsKind()
			throws Exception {
		byte[] a = key((byte) 3);
		byte[] b = key((byte) 4);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L), member(b, 1L));
		node.failCopiesTo(node.addContact(a));
		node.addContact(b);
		assertNull(node.manager.getLatestPostSummary(groupId));

		node.manager.sendGroupPostReporting(groupId, GroupTrBody.encodeImage(
				new byte[1024 * 1024], "image/jpeg"), 0L);

		GroupTrPostSummary remembered =
				node.manager.getLatestPostSummary(groupId);
		assertNotNull("the remembered answer takes the new post", remembered);
		assertEquals(GroupTrPostSummary.Kind.IMAGE, remembered.getKind());
		node.dropFromMemory(groupId);
		node.forgetReads();
		GroupTrPostSummary stored = node.manager.getLatestPostSummary(groupId);
		assertNotNull("the stored copy is summarised", stored);
		assertEquals(GroupTrPostSummary.Kind.IMAGE, stored.getKind());
		assertEquals("the kind was kept with the copy", 0L,
				node.largestBodyRead());
	}

	@Test
	public void aPostNoMemberGotIsNotSummarised() throws Exception {
		byte[] a = key((byte) 3);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		node.failCopiesTo(node.addContact(a));
		assertNull(node.manager.getLatestPostSummary(groupId));

		try {
			node.manager.sendGroupPostReporting(groupId,
					GroupTrBody.encodeText("hello"), 0L);
			fail("reported as sent while nobody got it");
		} catch (DbException expected) {
		}

		assertNull(node.manager.getLatestPostSummary(groupId));
		node.dropFromMemory(groupId);
		assertNull(node.manager.getLatestPostSummary(groupId));
	}

	@Test
	public void aPostEveryMemberIsTooOldForIsNotKept() throws Exception {
		byte[] a = key((byte) 3);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		node.announceMinorVersion(node.addContact(creatorKey),
				MessagingManager.GROUP_PROTOCOL_V2_MIN_VERSION - 1);
		node.announceMinorVersion(node.addContact(a),
				MessagingManager.GROUP_PROTOCOL_V2_MIN_VERSION - 1);
		assertNull(node.manager.getLatestPostSummary(groupId));

		GroupTrSendResult result = node.manager.sendGroupPostReporting(
				groupId, GroupTrBody.encodeImage(new byte[1024 * 1024],
						"image/jpeg"), 0L);

		assertEquals(2, result.getMembers());
		assertEquals(0, result.getReached());
		assertEquals("both members run an app too old for it", 2,
				result.getSkipped());
		assertNothingKept();
	}

	@Test
	public void aPostNoMemberIsAContactForIsNotKept() throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(key((byte) 3), 1L));
		assertNull(node.manager.getLatestPostSummary(groupId));

		GroupTrSendResult result = node.manager.sendGroupPostReporting(
				groupId, GroupTrBody.encodeText("hello"), 0L);

		assertEquals(2, result.getMembers());
		assertEquals(0, result.getRecipients());
		assertEquals(0, result.getSkipped());
		assertNothingKept();
	}

	@Test
	public void aPostInAGroupOfOneIsKept() throws Exception {
		node.putGroup(groupId, localKey, EPOCH, creator(localKey));
		assertNull(node.manager.getLatestPostSummary(groupId));

		GroupTrSendResult result = node.manager.sendGroupPostReporting(
				groupId, GroupTrBody.encodeText("hello"), 0L);

		assertEquals(0, result.getMembers());
		assertTrue(node.sent.isEmpty());
		List<GroupTrPost> shown = node.manager.getRecentPosts(groupId);
		assertEquals("the post is kept", 1, shown.size());
		assertArrayEquals(result.getPostId(), shown.get(0).getPostId());
		assertNotNull("it is the latest post of the group",
				node.manager.getLatestPostSummary(groupId));
		boolean announced = false;
		for (Object o : node.broadcasts) {
			if (o instanceof GroupTrPostAcceptedEvent) announced = true;
		}
		assertTrue("it is announced as a new post", announced);
	}

	private void assertNothingKept() throws Exception {
		assertTrue(node.sent.isEmpty());
		assertTrue("not shown as a post",
				node.manager.getRecentPosts(groupId).isEmpty());
		assertNull("not the latest post of the group",
				node.manager.getLatestPostSummary(groupId));
		for (Object o : node.broadcasts) {
			assertFalse("not announced as a new post",
					o instanceof GroupTrPostAcceptedEvent);
		}
		node.dropFromMemory(groupId);
		assertNull(node.manager.getLatestPostSummary(groupId));
	}

	@Test
	public void aPostIsSentEvenWhenTellingTheScreensFails()
			throws Exception {
		byte[] a = key((byte) 3);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L));
		node.addContact(a);
		node.failBroadcasts = true;

		GroupTrSendResult result = node.manager.sendGroupPostReporting(
				groupId, "hello".getBytes(StandardCharsets.UTF_8), 0L);

		assertEquals(1, result.getReached());
		assertEquals(1, node.sent.size());
		assertEquals(1, node.manager.getRecentPosts(groupId).size());
	}

	@Test
	public void theResultCountsEveryOtherMember() throws Exception {
		byte[] a = key((byte) 3);
		byte[] b = key((byte) 4);
		byte[] c = key((byte) 5);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(a, 1L), member(b, 1L),
				member(c, 1L));
		node.addContact(a);

		GroupTrSendResult result = node.manager.sendGroupPostReporting(
				groupId, "hello".getBytes(StandardCharsets.UTF_8), 0L);

		assertEquals("the creator and three members besides me", 4,
				result.getMembers());
		assertEquals(1, result.getRecipients());
		assertEquals(1, result.getReached());
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
