package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupTimerEnforcementTest {

	private static final long EPOCH = 5L;
	private static final long HOUR = 60L * 60L * 1000L;
	private static final long DAY = 24L * HOUR;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] bKey = key((byte) 4);

	@Test
	public void theCreatorsTimerIsSentToTheMembers() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(aKey, 1L), member(bKey, 1L));
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);

		node.manager.setGroupAutoDeleteTimer(groupId, DAY);

		assertEquals(DAY, node.group(groupId).getDefaultAutoDeleteTimerMs());
		for (ContactId c : Arrays.asList(a, b)) {
			BdfList record = recordTo(node, c, 46L);
			assertNotNull("the timer was not sent to a member", record);
			assertEquals(DAY, (long) record.getLong(2));
		}
	}

	@Test
	public void aPostWithoutATimerExpiresByTheGroupTimerOnReceipt()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.setGroupTimer(groupId, HOUR);
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		long sent = node.now;

		MessageId id = node.receive(a, groupId, aKey, EPOCH,
				"hello".getBytes("UTF-8"), sent, 0L, SIG);

		assertEquals(1, countFrom(node.manager.getRecentPosts(groupId), aKey));
		assertEquals("the stored post is not due when the group timer runs"
						+ " out", Long.valueOf(sent + HOUR),
				node.cleanupDeadline(id));
		node.now = sent + HOUR;
		assertEquals(0, countFrom(node.manager.getRecentPosts(groupId), aKey));
	}

	@Test
	public void aLongerTimerOfAPostIsShortenedToTheGroupTimer()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.setGroupTimer(groupId, HOUR);
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		long sent = node.now;

		MessageId id = node.receive(a, groupId, aKey, EPOCH,
				"hello".getBytes("UTF-8"), sent, DAY, SIG);

		assertEquals(Long.valueOf(sent + HOUR), node.cleanupDeadline(id));
		node.now = sent + HOUR;
		assertEquals(0, countFrom(node.manager.getRecentPosts(groupId), aKey));
		node.dropFromMemory(groupId);
		node.now = sent + HOUR - 1;
		List<GroupTrPost> reloaded = node.manager.getRecentPosts(groupId);
		assertEquals(1, countFrom(reloaded, aKey));
		assertEquals(HOUR, reloaded.get(0).getAutoDeleteTimerMs());
	}

	@Test
	public void ownPostsTakeTheGroupTimerAndTheirCopiesExpire()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.setGroupTimer(groupId, HOUR);
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);

		node.manager.sendGroupPost(groupId, "mine".getBytes("UTF-8"), 0L);

		for (ContactId c : Arrays.asList(a, b)) {
			BdfList post = recordTo(node, c, 32L);
			assertNotNull(post);
			assertEquals(8, post.size());
			assertEquals(HOUR, (long) post.getLong(7));
		}
		int withTimer = 0;
		for (MessageId id : node.storedIds()) {
			Long duration = node.cleanupTimerDuration(id);
			if (duration != null && duration == HOUR) withTimer++;
		}
		assertEquals("each kept copy of the own post carries its timer, which"
				+ " starts when the member acknowledges the copy", 2,
				withTimer);
	}

	private GroupTrTestNode memberDevice() {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		return node;
	}

	private static BdfList recordTo(GroupTrTestNode node, ContactId to,
			long type) throws Exception {
		for (GroupTrTestNode.Sent s : node.sent) {
			if (s.to.equals(to) && s.body.getLong(0) == type) return s.body;
		}
		return null;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
