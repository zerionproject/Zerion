package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupTimerArrivalTest {

	private static final long EPOCH = 5L;
	private static final long MINUTE = 60_000L;
	private static final long HOUR = 60L * MINUTE;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] bKey = key((byte) 4);
	private final byte[] strangerKey = key((byte) 5);

	@Test
	public void aPostThatArrivesAfterItsTimerIsShownForTheTimerFromArrival()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		long arrival = node.now;

		MessageId id = node.receive(a, groupId, aKey, EPOCH,
				"late".getBytes("UTF-8"), arrival - 2 * MINUTE, MINUTE, SIG);

		assertEquals("a post that took longer than its timer to arrive was"
				+ " lost", 1, countFrom(node.manager.getRecentPosts(groupId),
				aKey));
		assertTrue(node.isStored(id));
		assertEquals(Long.valueOf(arrival + MINUTE), node.cleanupDeadline(id));
		node.dropFromMemory(groupId);
		assertEquals("a load counts the timer from the sender's clock", 1,
				countFrom(node.manager.getRecentPosts(groupId), aKey));
		node.now = arrival + MINUTE;
		assertEquals(0, countFrom(node.manager.getRecentPosts(groupId), aKey));
	}

	@Test
	public void aPostDatedInTheFutureCannotOutliveTheGroupTimer()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.setGroupTimer(groupId, HOUR);
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		long arrival = node.now;

		MessageId id = node.receive(a, groupId, aKey, EPOCH,
				"ahead".getBytes("UTF-8"), arrival + 23 * HOUR, 0L, SIG);

		assertEquals(Long.valueOf(arrival + HOUR), node.cleanupDeadline(id));
		List<GroupTrPost> live = node.manager.getRecentPosts(groupId);
		assertEquals(1, countFrom(live, aKey));
		assertEquals(arrival + HOUR, live.get(0).getExpiryTime());
		node.now = arrival + HOUR;
		assertEquals(0, countFrom(node.manager.getRecentPosts(groupId), aKey));
	}

	@Test
	public void aShorterGroupTimerAppliesFromArrivalToAPostInFlight()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.setGroupTimer(groupId, 5 * MINUTE);
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		long arrival = node.now;

		MessageId id = node.receive(a, groupId, aKey, EPOCH,
				"in flight".getBytes("UTF-8"), arrival - 10 * MINUTE,
				24 * HOUR, SIG);

		assertEquals("a post in flight when the timer was shortened was"
				+ " removed on arrival", 1,
				countFrom(node.manager.getRecentPosts(groupId), aKey));
		assertEquals(Long.valueOf(arrival + 5 * MINUTE),
				node.cleanupDeadline(id));
	}

	@Test
	public void sentCopiesStartTheirTimerWhenTheMemberAcknowledgesThem()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.setGroupTimer(groupId, HOUR);
		node.addContact(aKey);
		node.addContact(bKey);

		node.manager.sendGroupPost(groupId, "mine".getBytes("UTF-8"), 0L);

		int withTimer = 0;
		for (MessageId id : node.storedIds()) {
			assertNull("a copy not yet acknowledged by the member is due for"
					+ " cleanup", node.cleanupDeadline(id));
			if (Long.valueOf(HOUR).equals(node.cleanupTimerDuration(id))) {
				withTimer++;
			}
		}
		assertEquals("each kept copy carries the post's timer", 2, withTimer);
	}

	@Test
	public void aPendingPostIsDueWhenTheGroupTimerRunsOut() throws Exception {
		GroupTrTestNode node = memberDevice();
		node.setGroupTimer(groupId, HOUR);
		ContactId stranger = node.addContact(strangerKey);
		node.manager.getRecentPosts(groupId);
		long arrival = node.now;

		MessageId id = node.receive(stranger, groupId, strangerKey, EPOCH,
				"held".getBytes("UTF-8"), arrival, 0L, SIG);

		assertTrue(node.isStored(id));
		assertEquals("a pending post is kept past the group timer",
				Long.valueOf(arrival + HOUR), node.cleanupDeadline(id));
	}

	private GroupTrTestNode memberDevice() {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		return node;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
