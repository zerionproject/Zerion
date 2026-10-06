package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.messaging.event.GroupTrInviteResponseReceivedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupInviteExpiryTest {

	private static final long EPOCH = 3L;
	private static final long DAY = 24L * 60L * 60L * 1000L;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] inviteeKey = key((byte) 2);
	private final byte[] memberKey = key((byte) 3);

	@Test
	public void anAcceptForAnInviteThatRanOutAddsNobody() throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId invitee = node.addContact(inviteeKey);
		node.manager.inviteContactToGroup(groupId, invitee, inviteeKey,
				"Invitee");
		node.now += 9 * DAY;
		node.sent.clear();

		accept(node, invitee, node.now);

		assertFalse("an invite answered after it ran out added the invitee",
				node.isMember(groupId, inviteeKey));
		assertTrue(node.sent.isEmpty());
	}

	@Test
	public void aTimelyAcceptAddsTheInvitee() throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId invitee = node.addContact(inviteeKey);
		node.manager.inviteContactToGroup(groupId, invitee, inviteeKey,
				"Invitee");
		node.now += DAY;

		accept(node, invitee, node.now);

		assertTrue(node.isMember(groupId, inviteeKey));
	}

	@Test
	public void anAcceptDatedBeforeTheInviteBeyondTheClockSkewIsIgnored()
			throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId invitee = node.addContact(inviteeKey);
		node.now += 2 * DAY;
		node.manager.inviteContactToGroup(groupId, invitee, inviteeKey,
				"Invitee");

		accept(node, invitee, node.now - 2 * DAY);

		assertFalse(node.isMember(groupId, inviteeKey));
	}

	@Test
	public void anOfferThatRanOutCannotBeAccepted() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(inviteeKey);
		ContactId fromCreator = node.addContact(creatorKey);
		node.putPendingOffer(groupId, fromCreator, creatorKey, node.now);
		node.now += 8 * DAY;

		boolean refused = false;
		try {
			node.manager.acceptInvite(groupId);
		} catch (DbException e) {
			refused = true;
		}

		assertNull("a group was joined from an offer that ran out",
				node.manager.getGroup(groupId));
		assertTrue(node.sent.isEmpty());
		assertTrue(refused);
	}

	private void accept(GroupTrTestNode node, ContactId from, long ts) {
		node.manager.eventOccurred(new GroupTrInviteResponseReceivedEvent(
				from, groupId, ts, SIG,
				GroupTrInviteResponseReceivedEvent.Kind.ACCEPT));
	}

	private GroupTrTestNode creatorDevice() {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey));
		node.addContact(memberKey);
		return node;
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
