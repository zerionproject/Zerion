package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.app.api.messaging.event.GroupTrPostAcceptedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.sync.MessageId;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.canonical;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.app.grouptr.GroupTrTestNode.member;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupJoiningPostsTest {

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] inviterKey = key((byte) 2);
	private final byte[] localKey = key((byte) 3);
	private final GroupTrTestNode node = new GroupTrTestNode(localKey);
	private ContactId fromInviter;

	@Before
	public void setUp() {
		node.putGroup(groupId, creatorKey, 0L, creator(creatorKey),
				member(inviterKey, 0L), member(localKey, 0L));
		node.putSetting(GroupTrConstants.SETTINGS_NS_PREFIX
						+ toHexString(groupId), GroupTrMemberInvites.S_JOINING_VIA,
				toHexString(inviterKey));
		fromInviter = node.addContact(inviterKey);
	}

	@Test
	public void postsWaitUntilTheCreatorConfirmsTheJoin() throws Exception {
		assertTrue(node.manager.isWaitingForAdmin(groupId));

		MessageId held = node.receive(fromInviter, groupId, inviterKey, 0L,
				"early".getBytes(StandardCharsets.UTF_8), node.now, 0L, SIG);

		assertTrue("a waiting invitee shows no post",
				node.manager.getRecentPosts(groupId).isEmpty());
		assertEquals(0, node.manager.getUnreadCount(groupId));
		assertFalse(accepted());
		assertTrue("the post is held, not dropped", node.isStored(held));

		node.snapshot(fromInviter, groupId, 1L, canonical(
				creatorKey, MemberRole.CREATOR, 0,
				inviterKey, MemberRole.MEMBER, 0,
				localKey, MemberRole.MEMBER, 1));

		assertFalse(node.manager.isWaitingForAdmin(groupId));
		assertEquals(1, node.manager.getRecentPosts(groupId).size());
		assertTrue(accepted());
	}

	private boolean accepted() {
		for (Object o : node.broadcasts) {
			if (o instanceof GroupTrPostAcceptedEvent && Arrays.equals(
					((GroupTrPostAcceptedEvent) o).getGroupId(), groupId)) {
				return true;
			}
		}
		return false;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
