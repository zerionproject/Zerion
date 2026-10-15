package org.zerionproject.app.grouptr;

import org.zerionproject.app.messaging.MessageTypes;
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
import static org.zerionproject.core.util.StringUtils.toHexString;

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
	public void anInvitationCardStaysOpenOnlyUntilItIsAnswered()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(inviteeKey);
		ContactId fromCreator = node.addContact(creatorKey);
		node.putPendingOffer(groupId, fromCreator, creatorKey, node.now);
		org.zerionproject.core.api.db.Transaction txn =
				new org.zerionproject.core.api.db.Transaction(null, true);
		assertTrue(node.manager.invitationStillOpen(txn,
				MessageTypes.GROUPTR_INVITE_OFFER, groupId));
		assertFalse(node.manager.invitationStillOpen(txn,
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER, groupId));

		node.manager.declineInvite(groupId);

		assertFalse("an answered invitation is no longer open",
				node.manager.invitationStillOpen(txn,
						MessageTypes.GROUPTR_INVITE_OFFER, groupId));
	}

	@Test
	public void anInvitationCardThatRanOutIsNoLongerOpen() throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(inviteeKey);
		ContactId fromCreator = node.addContact(creatorKey);
		byte[] memberGroupId = getRandomId();
		node.putPendingOffer(groupId, fromCreator, creatorKey, node.now);
		node.putPendingMemberOffer(memberGroupId, fromCreator, creatorKey,
				memberKey, node.now);
		org.zerionproject.core.api.db.Transaction txn =
				new org.zerionproject.core.api.db.Transaction(null, true);
		assertTrue(node.manager.invitationStillOpen(txn,
				MessageTypes.GROUPTR_INVITE_OFFER, groupId));
		assertTrue(node.manager.invitationStillOpen(txn,
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER, memberGroupId));

		node.now += 8 * DAY;

		assertFalse("an invitation that ran out is still open",
				node.manager.invitationStillOpen(txn,
						MessageTypes.GROUPTR_INVITE_OFFER, groupId));
		assertFalse(node.manager.invitationStillOpen(txn,
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER, memberGroupId));
	}

	@Test
	public void answeredAndExpiredInvitationsAreDeletedNotBlanked()
			throws Exception {
		GroupTrTestNode invitee = new GroupTrTestNode(inviteeKey);
		ContactId fromCreator = invitee.addContact(creatorKey);
		byte[] expired = getRandomId();
		invitee.putPendingOffer(groupId, fromCreator, creatorKey,
				invitee.now);
		invitee.putPendingOffer(expired, fromCreator, creatorKey,
				invitee.now - 8 * DAY);

		invitee.manager.declineInvite(groupId);
		invitee.manager.getPendingInvites();

		assertFalse(invitee.namespace(
				GroupTrConstants.SETTINGS_NS_OFFERS_PENDING)
				.containsKey(toHexString(groupId)));
		assertFalse(invitee.namespace(
				GroupTrConstants.SETTINGS_NS_OFFERS_PENDING)
				.containsKey(toHexString(expired)));

		GroupTrTestNode creator = new GroupTrTestNode(creatorKey, true);
		creator.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey));
		ContactId revoked = creator.addContact(inviteeKey);
		ContactId ranOut = creator.addContact(memberKey);
		creator.putSentInvite(groupId, revoked, inviteeKey, creator.now);
		creator.putSentInvite(groupId, ranOut, memberKey,
				creator.now - 15 * DAY);
		creator.putSetting(GroupTrConstants.SETTINGS_NS_INVITES_SENT,
				toHexString(groupId) + ":99", "0011");

		creator.manager.onDatabaseOpened(
				new org.zerionproject.core.api.db.Transaction(null, false));
		creator.manager.revokeInvite(groupId, revoked);
		creator.manager.getSentInvites(groupId);

		assertTrue("a sent invitation row is left",
				creator.namespace(GroupTrConstants.SETTINGS_NS_INVITES_SENT)
						.isEmpty());
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
