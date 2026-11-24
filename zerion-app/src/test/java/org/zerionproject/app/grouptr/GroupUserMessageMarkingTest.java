package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.messaging.event.GroupSettingsChangedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupUserMessageMarkingTest {

	private static final long EPOCH = 5L;
	private static final long DAY = 24L * 60L * 60L * 1000L;
	private static final long SETTINGS = 46L;
	private static final long POST = 32L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] bKey = key((byte) 4);

	@Test
	public void theOwnersSettingChangeIsMarkedForEveryMember()
			throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);

		node.manager.setGroupAutoDeleteTimer(groupId, DAY);

		List<MessageId> copies = new ArrayList<>(node.copiesTo(a));
		copies.addAll(node.copiesTo(b));
		assertEquals(2, copies.size());
		assertEquals(new HashSet<>(copies), new HashSet<>(node.userMessages));
	}

	@Test
	public void aPostIsMarkedButTheSettingHandedOnIsNot()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.deferTimers = true;
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		int before = node.copiesTo(a).size();
		node.userMessages.clear();
		settings(node, fromCreator, DAY, 10L, SIG);
		node.now += GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS;
		node.runDueTimers();

		node.manager.sendGroupPost(groupId, "one".getBytes("UTF-8"), 0L);

		Set<MessageId> marked = new HashSet<>(node.userMessages);
		List<MessageId> ids = node.copiesTo(a);
		List<GroupTrTestNode.Sent> records = recordsTo(node, a);
		assertEquals(ids.size(), records.size());
		int posts = 0, passedOn = 0;
		for (int i = before; i < ids.size(); i++) {
			long type = records.get(i).body.getLong(0);
			if (type == POST) {
				posts++;
				assertTrue("the post is a user message",
						marked.contains(ids.get(i)));
			} else if (type == SETTINGS) {
				passedOn++;
				assertFalse("a setting passed on is not",
						marked.contains(ids.get(i)));
			}
		}
		assertEquals(1, posts);
		assertEquals(1, passedOn);
	}

	@Test
	public void noHandOverOfTheSettingIsMarked() throws Exception {
		GroupTrTestNode node = memberDevice();
		node.deferTimers = true;
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		node.announceMinorVersion(b, 15);
		node.manager.getRecentPosts(groupId);

		settings(node, fromCreator, DAY, 10L, SIG);
		connected(node, a);
		assertEquals("handed over at the connection", 1,
				settingsTo(node, a));
		node.versionUpdated(b, 16);
		assertEquals("handed over at the update", 1, settingsTo(node, b));

		settings(node, fromCreator, DAY / 2, 20L, SIG);
		node.now += GroupTrManagerImpl.SETTINGS_PASS_MAX_DELAY_MS;
		node.runDueTimers();
		assertEquals("handed on by the pass after receipt", 2,
				settingsTo(node, a));
		assertEquals(2, settingsTo(node, b));

		node.receive(a, groupId, aKey, EPOCH, "one".getBytes("UTF-8"),
				node.now, 0L, SIG);
		assertEquals("sent in answer to a post without the timer", 3,
				settingsTo(node, a));

		assertTrue(node.userMessages.isEmpty());
	}

	@Test
	public void answeringAMemberWhoIsBehindMarksNothing() throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		node.addContact(bKey);
		node.manager.setGroupAutoDeleteTimer(groupId, DAY);
		node.manager.getRecentPosts(groupId);
		node.sent.clear();
		node.userMessages.clear();

		node.receive(a, groupId, aKey, EPOCH, "one".getBytes("UTF-8"),
				node.now, 0L, SIG);

		assertEquals("the owner answers with its setting", 1,
				recordsTo(node, a).size());
		assertEquals(SETTINGS, (long) recordsTo(node, a).get(0).body
				.getLong(0));
		assertTrue(node.userMessages.isEmpty());
	}

	private static void connected(GroupTrTestNode node, ContactId c) {
		node.manager.eventOccurred(new org.zerionproject.core.api.plugin
				.event.ContactConnectedEvent(c));
	}

	private static int settingsTo(GroupTrTestNode node, ContactId to)
			throws Exception {
		int count = 0;
		for (GroupTrTestNode.Sent s : recordsTo(node, to)) {
			if (s.body.getLong(0) == SETTINGS) count++;
		}
		return count;
	}

	private void settings(GroupTrTestNode node, ContactId from, long timer,
			long timestamp, byte[] sig) {
		node.manager.eventOccurred(new GroupSettingsChangedEvent(from,
				groupId, timer, timestamp, sig, GroupTrTestNode.SIGNED));
	}

	private GroupTrTestNode memberDevice() {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L), member(bKey, 1L));
		return node;
	}

	private GroupTrTestNode ownerDevice() {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(aKey, 1L), member(bKey, 1L));
		return node;
	}

	private static List<GroupTrTestNode.Sent> recordsTo(GroupTrTestNode node,
			ContactId to) {
		List<GroupTrTestNode.Sent> out = new ArrayList<>();
		for (GroupTrTestNode.Sent s : node.sent) {
			if (s.to.equals(to)) out.add(s);
		}
		return out;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
