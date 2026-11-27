package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrPost;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.GroupSettingsChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrInviteResponseReceivedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrRelayRecordEvent;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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

	@Test
	public void aCopyTheOwnerRelaysForAMemberIsNotMarked() throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		BdfList inner = BdfList.of(POST, groupId, EPOCH, aKey, "Name",
				"hello".getBytes("UTF-8"), SIG);
		BdfList keys = new BdfList();
		keys.add(bKey);
		BdfList record = BdfList.of((long) MessageTypes.GROUP_RELAY_REQUEST,
				groupId, keys, inner);

		node.manager.eventOccurred(new GroupTrRelayRecordEvent(a,
				new MessageId(getRandomId()), MessageTypes.GROUP_RELAY_REQUEST,
				record, node.now));

		assertEquals("the copy was not relayed", 1, typeTo(node, b, POST));
		assertTrue(node.userMessages.isEmpty());
	}

	@Test
	public void theOwnersRecordsAfterAMemberLeftAreNotMarked()
			throws Exception {
		GroupTrTestNode node = ownerDevice();
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);

		node.left(a, groupId, aKey, EPOCH + 1);

		assertFalse(node.isMember(groupId, aKey));
		assertEquals("the removal was not sent on", 1, typeTo(node, b, 34L));
		assertEquals(1, typeTo(node, b, 37L));
		assertTrue(node.userMessages.isEmpty());
	}

	@Test
	public void addingAnInviteeThatAcceptedIsNotMarked() throws Exception {
		byte[] inviteeKey = key((byte) 7);
		GroupTrTestNode node = ownerDevice();
		node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		ContactId invitee = node.addContact(inviteeKey);
		node.manager.inviteContactToGroup(groupId, invitee, inviteeKey,
				"Invitee");
		assertEquals("the invite the owner sends is the user's", 1,
				node.userMessages.size());
		node.userMessages.clear();
		node.sent.clear();

		node.manager.eventOccurred(new GroupTrInviteResponseReceivedEvent(
				invitee, groupId, node.now, SIG,
				GroupTrInviteResponseReceivedEvent.Kind.ACCEPT));

		assertTrue(node.isMember(groupId, inviteeKey));
		assertTrue("the members were not told", recordsTo(node, b).size() > 0);
		assertTrue(node.userMessages.isEmpty());
	}

	@Test
	public void aDeleteSentAgainToAMemberThatUpdatedIsNotMarked()
			throws Exception {
		GroupTrTestNode node = memberDevice();
		node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		node.announceMinorVersion(a,
				MessagingManager.GROUP_ACTIONS_MIN_VERSION - 1);
		node.manager.sendGroupPost(groupId, "mine".getBytes("UTF-8"), 0L);
		List<GroupTrPost> posts = node.manager.getRecentPosts(groupId);
		assertEquals(1, posts.size());
		node.manager.deletePostsForEveryone(groupId,
				Collections.singletonList(posts.get(0).getPostId()));
		assertFalse("the delete the user chose is marked",
				node.userMessages.isEmpty());
		node.userMessages.clear();
		node.sent.clear();

		node.versionUpdated(a, MessagingManager.GROUP_ACTIONS_MIN_VERSION);

		assertEquals("the delete was not sent again", 1, typeTo(node, a,
				(long) MessageTypes.GROUP_POST_DELETE));
		assertTrue(node.userMessages.isEmpty());
	}

	@Test
	public void leavingAGroupIsMarkedForEveryMember() throws Exception {
		GroupTrTestNode node = memberDevice();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId a = node.addContact(aKey);
		ContactId b = node.addContact(bKey);
		List<MessageId> before = new ArrayList<>();
		for (ContactId c : Arrays.asList(fromCreator, a, b)) {
			before.addAll(node.copiesTo(c));
		}

		node.manager.leaveGroup(groupId);

		List<MessageId> copies = new ArrayList<>();
		for (ContactId c : Arrays.asList(fromCreator, a, b)) {
			copies.addAll(node.copiesTo(c));
		}
		copies.removeAll(before);
		assertEquals(3, copies.size());
		assertEquals(new HashSet<>(copies), new HashSet<>(node.userMessages));
	}

	private static int typeTo(GroupTrTestNode node, ContactId to, long type)
			throws Exception {
		int count = 0;
		for (GroupTrTestNode.Sent s : recordsTo(node, to)) {
			if (s.body.getLong(0) == type) count++;
		}
		return count;
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
