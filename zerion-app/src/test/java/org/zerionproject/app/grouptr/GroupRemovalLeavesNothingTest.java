package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupRemovalLeavesNothingTest {

	private static final long EPOCH = 5L;

	private final byte[] groupId = getRandomId();
	private final String hex = toHexString(groupId);
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] otherKey = key((byte) 3);

	@Test
	public void removingAGroupDeletesItsSettingsRows() throws Exception {
		GroupTrTestNode node = memberNode();
		ContactId other = node.addContact(otherKey);
		fillLocalState(node, other);

		node.manager.removeFromDevice(groupId);

		assertNothingLeft(node);
	}

	@Test
	public void leftoversOfAGroupRemovedByAnEarlierVersionAreSwept()
			throws Exception {
		GroupTrTestNode node = memberNode();
		node.manager.setStealthName(groupId, "Live owl");
		node.putSetting("grouptr-seen:" + hex, "seen", "aa");
		String oldHex = toHexString(getRandomId());
		node.putSetting(GroupTrConstants.SETTINGS_NS_PREFIX + oldHex, "name",
				"");
		node.putSetting(GroupTrConstants.SETTINGS_NS_PREFIX + oldHex, "salt",
				"");
		node.putSetting(GroupTrConstants.SETTINGS_NS_PREFIX + oldHex,
				GroupTrConstants.S_REMOVED, "true");
		node.putSetting("grouptr.alias." + oldHex, "alias", "Old night owl");
		node.putSetting("grouptr-seen:" + oldHex, "seen", "cc,dd");
		node.putSetting(GroupTrConstants.SETTINGS_NS_LOCAL_PREFIX + oldHex,
				"blockScreenshots", "true");
		node.putSetting("grouptr-unread", oldHex, "0");
		node.putSetting("grouptr-unread", hex, "2");
		node.putSetting(GroupTrConstants.SETTINGS_NS_INVITES_SENT,
				oldHex + ":7", "");
		node.putSetting(GroupTrConstants.SETTINGS_NS_OFFERS_PENDING, oldHex,
				"");
		String answeredHex = toHexString(getRandomId());
		node.putSetting(GroupTrConstants.SETTINGS_NS_OFFERS_PENDING,
				answeredHex, "");
		ContactId inviter = node.addContact(creatorKey);
		byte[] invitedTo = getRandomId();
		node.putPendingOffer(invitedTo, inviter, creatorKey, node.now);
		String strayHex = toHexString(getRandomId());
		node.putSetting("grouptr-removed:" + strayHex, "deleted", "x.y.1");

		node.manager.sweepRemovedGroups();

		assertNull(node.namespace(GroupTrConstants.SETTINGS_NS_PREFIX
				+ oldHex));
		assertNull(node.namespace("grouptr.alias." + oldHex));
		assertNull(node.namespace("grouptr-seen:" + oldHex));
		assertNull(node.namespace(GroupTrConstants.SETTINGS_NS_LOCAL_PREFIX
				+ oldHex));
		assertNull(node.namespace("grouptr-removed:" + strayHex));
		Settings unread = node.namespace("grouptr-unread");
		assertNotNull(unread);
		assertFalse(unread.containsKey(oldHex));
		assertEquals("2", unread.get(hex));
		assertFalse(node.namespace(GroupTrConstants.SETTINGS_NS_INVITES_SENT)
				.containsKey(oldHex + ":7"));
		Settings offers =
				node.namespace(GroupTrConstants.SETTINGS_NS_OFFERS_PENDING);
		assertNotNull(offers);
		assertFalse(offers.containsKey(oldHex));
		assertFalse(offers.containsKey(answeredHex));
		assertNotNull("an open invitation stays",
				offers.get(toHexString(invitedTo)));
		assertNotNull("a live group keeps its state",
				node.namespace(GroupTrConstants.SETTINGS_NS_PREFIX + hex));
		assertNotNull(node.namespace("grouptr.alias." + hex));
		assertNotNull(node.namespace("grouptr-seen:" + hex));
	}

	@Test
	public void aGroupWhosePostsCouldNotBeDeletedKeepsItsState()
			throws Exception {
		GroupTrTestNode node = memberNode();
		ContactId other = node.addContact(otherKey);
		node.manager.setStealthName(groupId, "Night owl");
		MessageId post = node.storeAndPost(other, groupId, otherKey, EPOCH,
				"a post".getBytes("UTF-8"), 1_000L);
		node.failMessageRemovals(true);

		boolean failed = false;
		try {
			node.manager.removeFromDevice(groupId);
		} catch (DbException e) {
			failed = true;
		}

		assertTrue(failed);
		assertTrue(node.isStored(post));
		assertNotNull("the group must stay until its posts are deleted",
				node.manager.getGroup(groupId));
		assertEquals("Night owl", node.manager.getStealthName(groupId));

		node.failMessageRemovals(false);
		node.manager.removeFromDevice(groupId);

		assertFalse(node.isStored(post));
		assertNull(node.manager.getGroup(groupId));
		assertNull(node.namespace("grouptr.alias." + hex));
	}

	@Test
	public void removingAGroupDeletesItsStoredPosts() throws Exception {
		GroupTrTestNode node = memberNode();
		ContactId other = node.addContact(otherKey);
		node.manager.getRecentPosts(groupId);
		MessageId post = node.storeAndPost(other, groupId, otherKey, EPOCH,
				"a post".getBytes("UTF-8"), 1_000L);
		assertNotNull(node.metadata(post));
		assertEquals(1, node.cached(groupId).size());

		node.manager.removeFromDevice(groupId);

		assertNull(node.metadata(post));
		assertEquals(0, node.cached(groupId).size());
		assertTrue(node.manager.getRecentPosts(groupId).isEmpty());
	}

	@Test
	public void anExpiredPostLeavesMemoryWhenItsStoredCopyIsDeleted()
			throws Exception {
		GroupTrTestNode node = memberNode();
		ContactId other = node.addContact(otherKey);
		node.manager.getRecentPosts(groupId);
		MessageId post = node.storeAndPost(other, groupId, otherKey, EPOCH,
				"short lived".getBytes("UTF-8"), node.now, 1_000L);
		assertEquals(1, node.cached(groupId).size());

		node.now += 2_000L;
		node.manager.eventOccurred(new ConversationMessagesDeletedEvent(
				other, Collections.singletonList(post)));

		assertEquals(0, node.cached(groupId).size());
	}

	@Test
	public void leavingAGroupDeletesItsSettingsRows() throws Exception {
		GroupTrTestNode node = memberNode();
		node.addContact(creatorKey);
		ContactId other = node.addContact(otherKey);
		fillLocalState(node, other);

		node.manager.leaveGroup(groupId);

		assertNothingLeft(node);
	}

	@Test
	public void dissolvingAGroupDeletesItsSettingsRows() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(otherKey, 2L));
		node.addContact(localKey);
		ContactId other = node.addContact(otherKey);
		fillLocalState(node, other);

		node.manager.dissolveGroup(groupId);

		assertNothingLeft(node);
	}

	@Test
	public void beingRemovedByTheCreatorDeletesTheSettingsRows()
			throws Exception {
		GroupTrTestNode node = memberNode();
		ContactId fromCreator = node.addContact(creatorKey);
		ContactId other = node.addContact(otherKey);
		fillLocalState(node, other);

		node.removed(fromCreator, groupId, localKey, EPOCH);

		assertNothingLeft(node);
	}

	@Test
	public void clearingTheStealthNameDeletesIt() throws Exception {
		GroupTrTestNode node = memberNode();
		node.manager.setStealthName(groupId, "Night owl");
		assertEquals("Night owl", node.manager.getStealthName(groupId));

		node.manager.setStealthName(groupId, null);

		assertNull(node.manager.getStealthName(groupId));
		Settings alias = node.namespace("grouptr.alias." + hex);
		assertTrue(alias == null || alias.isEmpty());
	}

	private void fillLocalState(GroupTrTestNode node, ContactId other)
			throws Exception {
		node.manager.setStealthName(groupId, "Night owl");
		node.manager.setLocalScreenshotBlocked(groupId, true);
		node.putSetting("grouptr-seen:" + hex, "seen", "aa,bb");
		node.putSetting("grouptr-unread", hex, "3");
		node.putSetting("grouptr-unread", "kept", "1");
		node.putSentInvite(groupId, other, otherKey, 1_000L);
		node.putPendingOffer(groupId, other, creatorKey, 1_000L);
		for (String ns : namespaces()) {
			Settings s = node.namespace(ns);
			assertNotNull(ns, s);
			assertFalse(ns, s.isEmpty());
		}
	}

	private void assertNothingLeft(GroupTrTestNode node) throws Exception {
		for (String ns : namespaces()) {
			assertNull("a settings namespace of the group is left: " + ns,
					node.namespace(ns));
		}
		Settings unread = node.namespace("grouptr-unread");
		assertNotNull(unread);
		assertFalse(unread.containsKey(hex));
		assertEquals("1", unread.get("kept"));
		assertNoKeyFor(node.namespace(
				GroupTrConstants.SETTINGS_NS_INVITES_SENT));
		assertNoKeyFor(node.namespace(
				GroupTrConstants.SETTINGS_NS_OFFERS_PENDING));
		Settings index = node.namespace(GroupTrConstants.SETTINGS_NS_INDEX);
		assertNotNull(index);
		assertFalse(index.get(GroupTrConstants.S_GROUP_IDS).contains(hex));
		assertNull(node.manager.getGroup(groupId));
		assertNull(node.manager.getStealthName(groupId));
		assertFalse(node.manager.isLocalScreenshotBlocked(groupId));
	}

	private void assertNoKeyFor(@javax.annotation.Nullable Settings s) {
		if (s == null) return;
		for (String key : s.keySet()) {
			assertFalse("a row keyed by the group is left: " + key,
					key.startsWith(hex));
		}
	}

	private List<String> namespaces() {
		return Arrays.asList(GroupTrConstants.SETTINGS_NS_PREFIX + hex,
				GroupTrConstants.SETTINGS_NS_LOCAL_PREFIX + hex,
				"grouptr.alias." + hex, "grouptr-seen:" + hex);
	}

	private GroupTrTestNode memberNode() {
		GroupTrTestNode node = new GroupTrTestNode(localKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(otherKey, 2L));
		return node;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
