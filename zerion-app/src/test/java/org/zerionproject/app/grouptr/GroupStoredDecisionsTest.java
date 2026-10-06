package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.FORGED_SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupStoredDecisionsTest {

	private static final long EPOCH = 10L;
	private static final long MINUTE = 60_000L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] bKey = key((byte) 4);
	private final byte[] strangerKey = key((byte) 5);
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);

	@Test
	public void aPostFromBeforeItsSignerJoinedStaysHeldAfterAReload()
			throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, EPOCH - 2),
				member(bKey, 1L));
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);

		MessageId old = receive(a, groupId, aKey, EPOCH - 5,
				"old".getBytes("UTF-8"), node.now, 0L, SIG);
		receive(a, groupId, aKey, EPOCH, "current".getBytes("UTF-8"),
				node.now + 1, 0L, SIG);

		assertEquals(1, countFrom(node.manager.getRecentPosts(groupId), aKey));
		node.dropFromMemory(groupId);
		assertEquals("a post held on arrival was shown after a reload",
				1, countFrom(node.manager.getRecentPosts(groupId), aKey));
		assertTrue("a post held for its epoch was removed",
				node.isStored(old));
	}

	@Test
	public void aPostThatFailsTheFullSignatureIsRemovedAndNotCheckedAgain()
			throws Exception {
		group();
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);

		MessageId forged = receive(a, groupId, aKey, EPOCH,
				"forged".getBytes("UTF-8"), node.now, 0L, FORGED_SIG);

		assertFalse("a post that failed its check is kept",
				node.isStored(forged));
		node.dropFromMemory(groupId);
		int checksBefore = node.signatureChecks();
		node.manager.getRecentPosts(groupId);
		node.dropFromMemory(groupId);
		node.manager.getRecentPosts(groupId);
		assertEquals("a refused post is checked again at every load", 0,
				node.signatureChecks() - checksBefore);
	}

	@Test
	public void anAcceptedPostIsNotCheckedAgainWhenLoaded() throws Exception {
		group();
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		for (int i = 0; i < 20; i++) {
			receive(a, groupId, aKey, EPOCH, new byte[] {(byte) i},
					node.now + i, 0L, SIG);
		}
		node.dropFromMemory(groupId);
		int checksBefore = node.signatureChecks();

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals(20, countFrom(loaded, aKey));
		assertEquals(0, node.signatureChecks() - checksBefore);
	}

	@Test
	public void aPendingPostIsShownOnceItsSenderIsKnownAsAMember()
			throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L));
		ContactId a = node.addContact(aKey);
		ContactId fromCreator = node.addContact(creatorKey);
		node.manager.getRecentPosts(groupId);

		MessageId early = receive(a, groupId, aKey, EPOCH,
				"early".getBytes("UTF-8"), node.now, 0L, SIG);

		assertEquals(0, countFrom(node.manager.getRecentPosts(groupId), aKey));
		assertTrue("a post from a member not yet known is kept",
				node.isStored(early));
		node.added(fromCreator, groupId, aKey, EPOCH + 1);
		assertEquals(1, countFrom(node.manager.getRecentPosts(groupId), aKey));
	}

	@Test
	public void pendingPostsOfAContactAreBounded() throws Exception {
		group();
		ContactId stranger = node.addContact(strangerKey);
		node.manager.getRecentPosts(groupId);
		List<MessageId> ids = new ArrayList<>();
		for (int i = 0; i < 300; i++) {
			ids.add(receive(stranger, groupId, strangerKey, EPOCH,
					new byte[] {(byte) i}, node.now + i, 0L, SIG));
		}

		int kept = 0;
		for (MessageId id : ids) if (node.isStored(id)) kept++;
		assertTrue("a contact that is not a member kept " + kept
				+ " posts in the store", kept <= 125);
		assertTrue(node.isStored(ids.get(ids.size() - 1)));
	}

	@Test
	public void twoOwnPostsSentInTheSameMillisecondBothShowAfterALoad()
			throws Exception {
		group();
		node.addContact(aKey);
		node.manager.sendGroupPost(groupId, "one".getBytes("UTF-8"), 0L);
		node.manager.sendGroupPost(groupId, "two".getBytes("UTF-8"), 0L);
		node.dropFromMemory(groupId);

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals(2, countFrom(loaded, localKey));
	}

	@Test
	public void anOwnPostShowsWhenItsChosenCopyIsRemovedWhileLoading()
			throws Exception {
		group();
		node.addContact(aKey);
		node.addContact(bKey);
		node.manager.sendGroupPost(groupId, "mine".getBytes("UTF-8"), 0L);
		node.dropFromMemory(groupId);
		node.removeOnFirstRead(node.storedIds().get(0));

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals(1, countFrom(loaded, localKey));
	}

	@Test
	public void theLiveViewAndALoadAgreeWhenAPostExpires() throws Exception {
		group();
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		long sent = node.now;
		receive(a, groupId, aKey, EPOCH, "brief".getBytes("UTF-8"),
				sent, MINUTE, SIG);

		node.now = sent + MINUTE;
		int live = countFrom(node.manager.getRecentPosts(groupId), aKey);
		node.dropFromMemory(groupId);
		int loaded = countFrom(node.manager.getRecentPosts(groupId), aKey);

		assertEquals(loaded, live);
		assertEquals(0, live);
	}

	@Test
	public void aFloodingMemberDoesNotPushOthersOutOnALoad()
			throws Exception {
		group();
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		node.manager.getRecentPosts(groupId);
		for (int i = 0; i < 10; i++) {
			receive(b, groupId, bKey, EPOCH, new byte[] {(byte) i},
					node.now + i, 0L, SIG);
		}
		for (int i = 0; i < 400; i++) {
			receive(a, groupId, aKey, EPOCH, new byte[] {(byte) i, 1},
					node.now + 100 + i, 0L, SIG);
		}
		node.dropFromMemory(groupId);

		List<GroupTrPost> loaded = node.manager.getRecentPosts(groupId);

		assertEquals(10, countFrom(loaded, bKey));
		assertEquals(190, countFrom(loaded, aKey));
	}

	@Test
	public void theStoreIsNotReadAtEveryStart() throws Exception {
		group();
		ContactId a = node.addContact(aKey);
		for (int i = 0; i < 20; i++) {
			node.store(a, groupId, aKey, EPOCH, new byte[16], node.now + i,
					i % 2 == 0 ? MINUTE : 0L);
		}
		node.manager.onDatabaseOpened(new Transaction(null, false));
		node.forgetReads();

		node.manager.onDatabaseOpened(new Transaction(null, false));

		assertEquals("the stored posts were read again at a later start", 0,
				node.metadataReads());
		int due = 0;
		for (MessageId id : node.storedIds()) {
			if (node.cleanupDeadline(id) != null) due++;
		}
		assertEquals(10, due);
	}

	@Test
	public void aPostHeldForALaterEpochIsAnnouncedWhenReleased()
			throws Exception {
		group();
		ContactId a = node.addContact(aKey);
		ContactId fromCreator = node.addContact(creatorKey);
		node.manager.getRecentPosts(groupId);
		receive(a, groupId, aKey, EPOCH + 6, "ahead".getBytes("UTF-8"),
				node.now, 0L, SIG);
		assertEquals(0, countFrom(node.manager.getRecentPosts(groupId), aKey));
		node.broadcasts.clear();

		node.commit(fromCreator, groupId, EPOCH);

		assertEquals(1, countFrom(node.manager.getRecentPosts(groupId), aKey));
		boolean announced = false;
		for (Object e : node.broadcasts) {
			try {
				byte[] g = (byte[]) e.getClass().getMethod("getGroupId")
						.invoke(e);
				if (Arrays.equals(g, groupId)) announced = true;
			} catch (NoSuchMethodException ignored) {
			}
		}
		assertTrue("a released post waited for an unrelated event before"
				+ " the screen showed it", announced);
		assertEquals(1, node.manager.getUnreadCount(groupId));
	}

	boolean legacyFormat() {
		return false;
	}

	MessageId receive(ContactId from, byte[] groupId, byte[] sender,
			long epoch, byte[] body, long timestamp, long autoDeleteTimer,
			byte[] sig) {
		return legacyFormat()
				? node.receiveLegacy(from, groupId, sender, epoch, body,
				timestamp, autoDeleteTimer, sig)
				: node.receive(from, groupId, sender, epoch, body, timestamp,
				autoDeleteTimer, sig);
	}

	private void group() {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
