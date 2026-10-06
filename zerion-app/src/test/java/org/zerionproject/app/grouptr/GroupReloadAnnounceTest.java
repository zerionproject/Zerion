package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.messaging.event.GroupTrPostAcceptedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.countFrom;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupReloadAnnounceTest {

	private static final long EPOCH = 5L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private final byte[] aKey = key((byte) 3);
	private final byte[] strangerKey = key((byte) 4);
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);

	@Test
	public void aCopyOfTheUsersOwnPostIsAnnouncedAsLocal() throws Exception {
		group();
		ContactId a = node.addContact(aKey);
		node.manager.getRecentPosts(groupId);
		node.broadcasts.clear();

		node.receive(a, groupId, localKey, EPOCH, "mine".getBytes("UTF-8"),
				node.now, 0L, SIG);

		GroupTrPostAcceptedEvent announced = null;
		for (Object e : node.broadcasts) {
			if (e instanceof GroupTrPostAcceptedEvent) {
				announced = (GroupTrPostAcceptedEvent) e;
			}
		}
		assertNotNull(announced);
		assertTrue("a copy of the user's own post was announced as not local",
				announced.isLocal());
		assertEquals(0, node.manager.getUnreadCount(groupId));
	}

	@Test
	public void aStoredPostWithTheLocalKeyIsCheckedBeforeItIsShownAsOwn()
			throws Exception {
		group();
		ContactId stranger = node.addContact(strangerKey);
		node.storeCurrent(stranger, groupId, localKey, EPOCH,
				"not mine".getBytes("UTF-8"), node.now, 0L, SIG);

		assertEquals("a post with this device's key that a non-member"
						+ " delivered was shown as the user's own", 0,
				countFrom(node.manager.getRecentPosts(groupId), localKey));
	}

	@Test
	public void aPostAcceptedWhileLoadingIsCountedAndAnnounced()
			throws Exception {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L));
		ContactId a = node.addContact(aKey);
		ContactId fromCreator = node.addContact(creatorKey);
		node.manager.getRecentPosts(groupId);
		node.receive(a, groupId, aKey, EPOCH, "early".getBytes("UTF-8"),
				node.now, 0L, SIG);
		assertEquals(0, node.manager.getUnreadCount(groupId));
		node.added(fromCreator, groupId, aKey, EPOCH + 1);
		node.broadcasts.clear();

		assertEquals(1, countFrom(node.manager.getRecentPosts(groupId), aKey));

		assertEquals("a post accepted while loading is not counted as unread",
				1, node.manager.getUnreadCount(groupId));
		boolean announced = false;
		for (Object e : node.broadcasts) {
			if (e instanceof GroupTrPostAcceptedEvent
					&& !((GroupTrPostAcceptedEvent) e).isLocal()) {
				announced = true;
			}
		}
		assertTrue("a post accepted while loading was not announced",
				announced);
	}

	private void group() {
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey),
				member(localKey, 1L), member(aKey, 1L));
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
