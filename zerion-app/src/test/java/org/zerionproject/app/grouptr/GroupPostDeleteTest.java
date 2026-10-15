package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupPostDeleteReceivedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrPostsRemovedEvent;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.grouptr.GroupTrTestNode.FORGED_SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupPostDeleteTest {

	private static final long EPOCH = 3L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] authorKey = key((byte) 3);
	private final byte[] strangerKey = key((byte) 4);

	private GroupTrTestNode node;
	private ContactId fromCreator;
	private ContactId fromAuthor;

	@Before
	public void setUp() {
		node = new GroupTrTestNode(localKey);
		fromCreator = node.addContact(creatorKey);
		fromAuthor = node.addContact(authorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 0L), member(authorKey, 0L));
	}

	@Test
	public void theAuthorsDeleteRemovesThePost() throws Exception {
		MessageId copy = receive("hello", 1_000L);
		byte[] postId = onlyPost().getPostId();
		assertArrayEquals(postId("hello", 1_000L), postId);

		delete(fromAuthor, authorKey, postId, SIG);

		assertFalse(node.isStored(copy));
		assertTrue(node.manager.getRecentPosts(groupId).isEmpty());
		GroupTrPostsRemovedEvent removed = lastRemoval();
		assertArrayEquals(postId, removed.getPostIds().get(0));
	}

	@Test
	public void aDeleteByAnyoneButTheAuthorIsIgnored() throws Exception {
		MessageId copy = receive("hello", 1_000L);
		byte[] postId = onlyPost().getPostId();

		delete(fromCreator, creatorKey, postId, SIG);
		delete(fromAuthor, authorKey, postId, FORGED_SIG);
		ContactId stranger = node.addContact(strangerKey);
		delete(stranger, authorKey, postId, SIG);

		assertTrue(node.isStored(copy));
		assertEquals(1, node.manager.getRecentPosts(groupId).size());
	}

	@Test
	public void aDeleteThatArrivesFirstIsKeptAndAppliedToItsPost()
			throws Exception {
		delete(fromAuthor, authorKey, postId("early", 1_000L), SIG);

		MessageId copy = receive("early", 1_000L);

		assertFalse("the post is dropped on arrival", node.isStored(copy));
		assertTrue(node.manager.getRecentPosts(groupId).isEmpty());
		assertEquals(0, node.manager.getUnreadCount(groupId));
	}

	@Test
	public void aPendingDeleteOfAnotherSenderDoesNotDropThePost()
			throws Exception {
		delete(fromCreator, creatorKey, postId("early", 1_000L), SIG);

		MessageId copy = receive("early", 1_000L);

		assertTrue(node.isStored(copy));
		assertEquals(1, node.manager.getRecentPosts(groupId).size());
	}

	@Test
	public void aPostThatArrivesAgainAfterItsDeleteIsDroppedUnchecked()
			throws Exception {
		receive("again", 1_000L);
		delete(fromAuthor, authorKey, onlyPost().getPostId(), SIG);
		int checks = node.signatureChecks();

		MessageId copy = receive("again", 1_000L);

		assertFalse(node.isStored(copy));
		assertEquals("no signature is checked for a deleted post", checks,
				node.signatureChecks());
		assertTrue(node.manager.getRecentPosts(groupId).isEmpty());
	}

	@Test
	public void waitingDeletesAreBoundedPerGroup() throws Exception {
		int count = GroupTrPostRemovals.MAX_PENDING_PER_GROUP + 1;
		for (int i = 0; i < count; i++) {
			delete(fromAuthor, authorKey, postId("p" + i, 1_000L + i), SIG);
		}

		MessageId oldest = receive("p0", 1_000L);
		MessageId newest = receive("p" + (count - 1), 1_000L + count - 1);

		assertTrue("the oldest waiting delete gave way", node.isStored(oldest));
		assertFalse(node.isStored(newest));
	}

	@Test
	public void oneMembersWaitingDeletesDoNotPushOutAnothers()
			throws Exception {
		delete(fromAuthor, authorKey, postId("kept", 1_000L), SIG);
		for (int i = 0; i <= GroupTrPostRemovals.MAX_PENDING_PER_GROUP; i++) {
			delete(fromCreator, creatorKey, getRandomId(), SIG);
		}

		MessageId copy = receive("kept", 1_000L);

		assertFalse("another member's deletes pushed this one out",
				node.isStored(copy));
	}

	@Test
	public void waitingDeletesRunOutAfterSevenDays() throws Exception {
		delete(fromAuthor, authorKey, postId("late", 1_000L), SIG);
		node.now += GroupTrPostRemovals.KEPT_MS + 1L;

		MessageId copy = receive("late", 1_000L);

		assertTrue(node.isStored(copy));
	}

	@Test
	public void deletingForEveryoneSendsOnlyToMembersThatCanApplyIt()
			throws Exception {
		node.announceMinorVersion(fromAuthor,
				MessagingManager.GROUP_ACTIONS_MIN_VERSION - 1);
		node.manager.sendGroupPost(groupId, "mine".getBytes(
				StandardCharsets.UTF_8), 0L);
		GroupTrPost mine = onlyPost();
		assertEquals("one copy per member", 2, storedPosts().size());
		node.sent.clear();

		int keeping = node.manager.deletePostsForEveryone(groupId,
				Collections.singletonList(mine.getPostId()));

		assertEquals(1, keeping);
		assertEquals(1, node.sent.size());
		assertEquals(fromCreator, node.sent.get(0).to);
		BdfList record = node.sent.get(0).body;
		assertEquals(7, record.size());
		assertEquals(MessageTypes.GROUP_POST_DELETE, record.getLong(0)
				.intValue());
		assertArrayEquals(groupId, record.getRaw(1));
		assertEquals(EPOCH, (long) record.getLong(2));
		assertArrayEquals(localKey, record.getRaw(3));
		assertArrayEquals(mine.getPostId(), record.getRaw(4));
		assertEquals(node.now, (long) record.getLong(5));
		assertEquals(PostQuantumConstants.HYBRID_SIGNATURE_BYTES,
				record.getRaw(6).length);
		assertTrue("every local copy is removed", storedPosts().isEmpty());
		assertTrue(node.manager.getRecentPosts(groupId).isEmpty());
	}

	@Test
	public void aMemberThatUpdatesLaterReceivesTheDelete() throws Exception {
		node.announceMinorVersion(fromAuthor,
				MessagingManager.GROUP_ACTIONS_MIN_VERSION - 1);
		node.manager.sendGroupPost(groupId, "mine".getBytes(
				StandardCharsets.UTF_8), 0L);
		GroupTrPost mine = onlyPost();
		node.manager.deletePostsForEveryone(groupId,
				Collections.singletonList(mine.getPostId()));
		BdfList first = node.sent.get(node.sent.size() - 1).body;
		node.sent.clear();

		node.versionUpdated(fromAuthor,
				MessagingManager.GROUP_ACTIONS_MIN_VERSION);

		List<BdfList> deletes = new ArrayList<>();
		for (GroupTrTestNode.Sent sent : node.sent) {
			if (sent.body.getLong(0).intValue()
					!= MessageTypes.GROUP_POST_DELETE) {
				continue;
			}
			assertEquals(fromAuthor, sent.to);
			deletes.add(sent.body);
		}
		assertEquals(1, deletes.size());
		BdfList resent = deletes.get(0);
		assertArrayEquals(mine.getPostId(), resent.getRaw(4));
		assertEquals(first.getLong(2), resent.getLong(2));
		assertEquals(first.getLong(5), resent.getLong(5));
		assertArrayEquals(GroupTrPostRemovals.signedInput(groupId,
				first.getLong(2), localKey, mine.getPostId(),
				first.getLong(5)), GroupTrPostRemovals.signedInput(groupId,
				resent.getLong(2), localKey, resent.getRaw(4),
				resent.getLong(5)));

		node.sent.clear();
		node.versionUpdated(fromAuthor,
				MessagingManager.GROUP_ACTIONS_MIN_VERSION);
		node.now += GroupTrPostRemovals.KEPT_MS + 1;
		node.sent.clear();
		node.versionUpdated(fromAuthor,
				MessagingManager.GROUP_ACTIONS_MIN_VERSION);
		for (GroupTrTestNode.Sent sent : node.sent) {
			assertTrue("a delete older than seven days is not resent",
					sent.body.getLong(0).intValue()
							!= MessageTypes.GROUP_POST_DELETE);
		}
	}

	@Test
	public void theSentDeleteIsKeptForResendingUntilAcknowledged()
			throws Exception {
		node.manager.sendGroupPost(groupId, "mine".getBytes(
				StandardCharsets.UTF_8), 0L);
		byte[] postId = onlyPost().getPostId();

		node.manager.deletePostsForEveryone(groupId,
				Collections.singletonList(postId));

		int records = 0;
		for (MessageId id : node.storedIds()) {
			BdfDictionary meta = node.metadata(id);
			if (meta == null || !Long.valueOf(MessageTypes.GROUP_POST_DELETE)
					.equals(meta.getOptionalLong("messageType"))) {
				continue;
			}
			records++;
			assertEquals(Long.valueOf(5L * 60L * 1000L),
					node.cleanupTimerDuration(id));
			assertEquals(null, node.cleanupDeadline(id));
		}
		assertEquals(2, records);
	}

	@Test
	public void onlyTheAuthorCanDeleteForEveryone() throws Exception {
		MessageId copy = receive("theirs", 1_000L);
		node.sent.clear();

		int keeping = node.manager.deletePostsForEveryone(groupId,
				Collections.singletonList(onlyPost().getPostId()));

		assertEquals(0, keeping);
		assertTrue(node.sent.isEmpty());
		assertTrue(node.isStored(copy));
	}

	@Test
	public void deletingForEveryoneNeedsMembership() throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(authorKey, 0L));
		try {
			node.manager.deletePostsForEveryone(groupId,
					Collections.singletonList(new byte[32]));
			fail();
		} catch (GroupTrAuthException expected) {
			assertEquals(GroupTrAuthException.Reason.NOT_A_MEMBER,
					expected.getReason());
		}
	}

	@Test
	public void deletingForMeRemovesEveryCopyAndSendsNothing()
			throws Exception {
		MessageId copy = receive("theirs", 1_000L);
		node.sent.clear();

		node.manager.deletePostsForMe(groupId,
				Collections.singletonList(onlyPost().getPostId()));

		assertFalse(node.isStored(copy));
		assertTrue(node.sent.isEmpty());
		assertTrue(node.manager.getRecentPosts(groupId).isEmpty());
		MessageId again = receive("theirs", 1_000L);
		assertFalse("it does not come back", node.isStored(again));
	}

	@Test
	public void deletingAnUnreadPostLowersTheUnreadCount() throws Exception {
		receive("first", 1_000L);
		node.now += 10L;
		receive("second", 2_000L);
		assertEquals(2, node.manager.getUnreadCount(groupId));

		delete(fromAuthor, authorKey, postId("second", 2_000L), SIG);

		assertEquals(1, node.manager.getUnreadCount(groupId));
		assertEquals(1, lastRemoval().getUnreadRemoved());
	}

	@Test
	public void aPostHeldUntilAfterTheGroupWasReadStillCountsAsUnread()
			throws Exception {
		node.now += 10L;
		long arrivedBeforeTheRead = node.now;
		node.now += 10L;
		node.manager.markGroupRead(groupId);
		node.now += 10L;
		MessageId held = receive("held", 1_000L);
		assertEquals(1, node.manager.getUnreadCount(groupId));
		BdfDictionary meta = node.metadata(held);
		meta.put(MessagingManager.MSG_KEY_GROUP_RECEIVED_AT,
				arrivedBeforeTheRead);
		assertTrue(meta.getLong(GroupTrConstants.MSG_KEY_GROUP_ACCEPTED_AT)
				> arrivedBeforeTheRead);

		delete(fromAuthor, authorKey, postId("held", 1_000L), SIG);

		assertEquals(0, node.manager.getUnreadCount(groupId));
		assertEquals(1, lastRemoval().getUnreadRemoved());
	}

	@Test
	public void deletingAReadPostLeavesTheUnreadCount() throws Exception {
		receive("read", 1_000L);
		node.now += 10L;
		node.manager.markGroupRead(groupId);
		node.now += 10L;
		receive("unread", 2_000L);
		assertEquals(1, node.manager.getUnreadCount(groupId));

		delete(fromAuthor, authorKey, postId("read", 1_000L), SIG);

		assertEquals(1, node.manager.getUnreadCount(groupId));
		assertEquals(0, lastRemoval().getUnreadRemoved());
	}

	@Test
	public void deletingAPostReadBeforeReadTimesWereKeptLeavesTheCount()
			throws Exception {
		receive("old", 1_000L);
		readBeforeReadTimesWereKept(0);
		node.now += 10L;
		receive("new", 2_000L);
		assertEquals(1, node.manager.getUnreadCount(groupId));

		delete(fromAuthor, authorKey, postId("old", 1_000L), SIG);

		assertEquals(1, node.manager.getUnreadCount(groupId));
		assertEquals(0, lastRemoval().getUnreadRemoved());
	}

	@Test
	public void withoutAReadTimeADeleteDoesNotLowerTheCount()
			throws Exception {
		receive("old", 1_000L);
		node.now += 10L;
		receive("new", 2_000L);
		readBeforeReadTimesWereKept(1);

		delete(fromAuthor, authorKey, postId("old", 1_000L), SIG);

		assertEquals(1, node.manager.getUnreadCount(groupId));
		assertEquals(0, lastRemoval().getUnreadRemoved());
	}

	@Test
	public void membersAreCountedByWhetherTheyCanApplyADelete()
			throws Exception {
		node.announceMinorVersion(fromCreator,
				MessagingManager.GROUP_ACTIONS_MIN_VERSION - 1);
		node.addContact(strangerKey);

		assertEquals(1, node.manager.countMembersWithPostActions(groupId));
		assertEquals(1, node.manager.countMembersWithoutPostActions(groupId));
	}

	@Test
	public void theSignedInputIsByteExact() {
		byte[] target = key((byte) 7);
		byte[] signed = GroupTrPostRemovals.signedInput(groupId, 0x01020304L,
				authorKey, target, 0x0102030405060708L);
		ByteBuffer expected = ByteBuffer.allocate(109);
		expected.put(groupId).putInt(0x01020304).put(authorKey).put(target)
				.putLong(0x0102030405060708L).put((byte) 0x09);
		assertArrayEquals(expected.array(), signed);
		assertEquals("org.zerionproject/GROUP_POST_DELETE",
				GroupTrConstants.SIGNING_LABEL_GROUP_POST_DELETE);
	}

	private MessageId receive(String text, long timestamp) {
		return node.receive(fromAuthor, groupId, authorKey, EPOCH,
				text.getBytes(StandardCharsets.UTF_8), timestamp, 0L, SIG);
	}

	private void readBeforeReadTimesWereKept(int unread) {
		String hex = toHexString(groupId);
		node.putSetting("grouptr-unread", hex, Integer.toString(unread));
		Settings kept = node.namespace(GroupTrPostRemovals.NAMESPACE_PREFIX
				+ hex);
		if (kept != null) kept.remove("readAt");
	}

	private void delete(ContactId from, byte[] author, byte[] target,
			byte[] sig) {
		node.manager.eventOccurred(new GroupPostDeleteReceivedEvent(from,
				new MessageId(getRandomId()), groupId, EPOCH, author, target,
				node.now, sig));
	}

	private GroupTrPost onlyPost() {
		List<GroupTrPost> posts = node.manager.getRecentPosts(groupId);
		assertEquals(1, posts.size());
		return posts.get(0);
	}

	private List<MessageId> storedPosts() throws Exception {
		List<MessageId> out = new ArrayList<>();
		for (MessageId id : node.storedIds()) {
			BdfDictionary meta = node.metadata(id);
			if (meta != null && Long.valueOf(MessageTypes.GROUP_POST)
					.equals(meta.getOptionalLong("messageType"))) {
				out.add(id);
			}
		}
		return out;
	}

	private GroupTrPostsRemovedEvent lastRemoval() {
		GroupTrPostsRemovedEvent last = null;
		for (Object b : node.broadcasts) {
			if (b instanceof GroupTrPostsRemovedEvent) {
				last = (GroupTrPostsRemovedEvent) b;
			}
		}
		if (last == null) throw new AssertionError("nothing removed");
		return last;
	}

	private byte[] postId(String text, long timestamp) {
		byte[] body = text.getBytes(StandardCharsets.UTF_8);
		ByteBuffer signed = ByteBuffer.allocate(32 + 4 + 32 + 32 + 32 + 8 + 8);
		signed.put(groupId).putInt((int) EPOCH).put(authorKey)
				.put(sha256("org.zerionproject/GROUP_POST_NAME",
						"Name".getBytes(StandardCharsets.UTF_8)))
				.put(sha256(MessagingManager.GROUP_POST_BODY_HASH_LABEL, body))
				.putLong(timestamp).putLong(0L);
		return sha256("org.zerionproject/GROUP_POST_SEEN", signed.array());
	}

	private static byte[] sha256(String label, byte[] input) {
		try {
			MessageDigest d = MessageDigest.getInstance("SHA-256");
			d.update(label.getBytes(StandardCharsets.UTF_8));
			d.update(input);
			return d.digest();
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new AssertionError(e);
		}
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
