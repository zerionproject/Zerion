package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.messaging.event.GroupSettingsChangedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfList;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.zerionproject.app.grouptr.GroupTrTestNode.FORGED_SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupSettingsRecordTest {

	private static final long EPOCH = 5L;
	private static final long HOUR = 60L * 60L * 1000L;
	private static final long DAY = 24L * HOUR;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] newKey = key((byte) 4);

	@Test
	public void theCreatorsSignedTimerIsApplied() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);

		ContactId toMember = node.addContact(aKey);

		settings(node, fromCreator, DAY, 10L, SIG);

		assertEquals(DAY, node.group(groupId).getDefaultAutoDeleteTimerMs());
		node.manager.sendGroupPost(groupId, "x".getBytes("UTF-8"), 0L);
		BdfList post = null;
		for (GroupTrTestNode.Sent s : node.sent) {
			if (s.to.equals(toMember)) post = s.body;
		}
		if (post == null) throw new AssertionError("post not sent");
		assertEquals("a member's own posts carry the group's timer", DAY,
				(long) post.getLong(7));
	}

	@Test
	public void settingsFromAnyoneButTheCreatorAreRefused() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromMember = node.addContact(aKey);

		settings(node, fromMember, DAY, 10L, SIG);

		assertEquals(0L, node.group(groupId).getDefaultAutoDeleteTimerMs());
	}

	@Test
	public void olderOrForgedOrOutOfRangeSettingsAreRefused()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		settings(node, fromCreator, DAY, 10L, SIG);

		settings(node, fromCreator, HOUR, 9L, SIG);
		settings(node, fromCreator, HOUR, 10L, SIG);
		settings(node, fromCreator, HOUR, 11L, FORGED_SIG);
		settings(node, fromCreator, 1L, 12L, SIG);
		assertEquals(DAY, node.group(groupId).getDefaultAutoDeleteTimerMs());

		settings(node, fromCreator, 0L, 13L, SIG);
		assertEquals(0L, node.group(groupId).getDefaultAutoDeleteTimerMs());
	}

	@Test
	public void membersOnAnEarlierVersionAreNotSentTheRecordAndAreCounted()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(aKey, 1L), member(localKey, 1L));
		ContactId current = node.addContact(aKey);
		ContactId earlier = node.addContact(localKey);
		node.announceMinorVersion(earlier, 7);

		node.manager.setGroupAutoDeleteTimer(groupId, DAY);

		assertEquals(1, node.manager.countMembersOnOlderVersion(groupId));
		assertEquals(1, node.sent.size());
		assertEquals(current, node.sent.get(0).to);
	}

	@Test
	public void aMemberAddedLaterReceivesTheTimerInForce() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(aKey, 1L));
		node.addContact(aKey);
		ContactId added = node.addContact(newKey);
		node.manager.setGroupAutoDeleteTimer(groupId, DAY);
		node.sent.clear();

		node.manager.addMember(groupId, newKey, "New");

		BdfList record = null;
		for (GroupTrTestNode.Sent s : node.sent) {
			if (s.to.equals(added) && s.body.getLong(0) == 46L) {
				record = s.body;
			}
		}
		if (record == null) throw new AssertionError("no settings record");
		assertArrayEquals(groupId, record.getRaw(1));
		assertEquals(DAY, (long) record.getLong(2));
	}

	@Test
	public void aTimerOutsideTheRangeOfAConversationTimerIsRefused()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(aKey, 1L));
		try {
			node.manager.setGroupAutoDeleteTimer(groupId, 10L);
			fail();
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.INVALID_TIMER,
					e.getReason());
		}
	}

	private void settings(GroupTrTestNode node, ContactId from, long timer,
			long timestamp, byte[] sig) {
		node.manager.eventOccurred(new GroupSettingsChangedEvent(from,
				groupId, timer, timestamp, sig, GroupTrTestNode.SIGNED));
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
