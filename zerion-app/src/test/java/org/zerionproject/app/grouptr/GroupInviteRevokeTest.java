package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.grouptr.GroupTrSentInvite;
import org.zerionproject.app.api.messaging.event.GroupTrInviteResponseReceivedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupInviteRevokeTest {

	private static final long EPOCH = 3L;
	private static final long DAY = 24L * 60L * 60L * 1000L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] inviteeKey = key((byte) 2);
	private final byte[] otherKey = key((byte) 3);

	@Test
	public void aRevokedInviteIgnoresTheAnswer() throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId invitee = node.addContact(inviteeKey);
		ContactId other = node.addContact(otherKey);
		node.manager.inviteContactToGroup(groupId, invitee, inviteeKey,
				"Invitee");
		node.manager.inviteContactToGroup(groupId, other, otherKey, "Other");
		assertEquals(2, node.manager.getSentInvites(groupId).size());

		node.manager.revokeInvite(groupId, invitee);
		node.manager.eventOccurred(new GroupTrInviteResponseReceivedEvent(
				invitee, groupId, node.now, SIG,
				GroupTrInviteResponseReceivedEvent.Kind.ACCEPT));

		assertFalse(node.isMember(groupId, inviteeKey));
		Collection<GroupTrSentInvite> open =
				node.manager.getSentInvites(groupId);
		assertEquals(1, open.size());
		assertEquals(other, open.iterator().next().getContactId());
	}

	@Test
	public void anInviteThatRanOutIsNoLongerListed() throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId invitee = node.addContact(inviteeKey);
		node.manager.inviteContactToGroup(groupId, invitee, inviteeKey,
				"Invitee");
		node.now += 15 * DAY;

		assertTrue(node.manager.getSentInvites(groupId).isEmpty());
	}

	@Test
	public void acceptingAnOfferThatRanOutIsRefusedAsExpired()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(inviteeKey);
		ContactId fromCreator = node.addContact(creatorKey);
		node.putPendingOffer(groupId, fromCreator, creatorKey, node.now);
		node.now += 8 * DAY;
		try {
			node.manager.acceptInvite(groupId);
			fail();
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.INVITE_EXPIRED,
					e.getReason());
		}
		assertTrue(node.manager.getPendingInvites().isEmpty());
	}

	private GroupTrTestNode creatorDevice() {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey));
		return node;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
