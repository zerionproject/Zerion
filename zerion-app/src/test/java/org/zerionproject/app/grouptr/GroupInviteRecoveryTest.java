package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.messaging.event.GroupTrInviteResponseReceivedEvent;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.Transaction;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupTrTestNode.SIG;
import static org.zerionproject.app.grouptr.GroupTrTestNode.creator;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupInviteRecoveryTest {

	private static final long EPOCH = 3L;
	private static final long MINUTE = 60_000L;
	private static final long HOUR = 60L * MINUTE;
	private static final long DAY = 24L * HOUR;

	private final byte[] groupId = getRandomId();
	private final byte[] creatorKey = key((byte) 1);
	private final byte[] inviteeKey = key((byte) 2);
	private final byte[] otherKey = key((byte) 3);

	@Test
	public void anAnswerThatArrivesAfterTheInviteRanOutIsTakenInTheGrace()
			throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId invitee = node.addContact(inviteeKey);
		long sent = node.now;
		node.manager.inviteContactToGroup(groupId, invitee, inviteeKey,
				"Invitee");
		long answered = sent + 6 * DAY + 22 * HOUR;
		node.now = sent + 7 * DAY + 5 * HOUR;

		accept(node, invitee, answered);

		assertTrue("an answer signed while the invite was open was refused"
				+ " because the creator was out of reach when it was given",
				node.isMember(groupId, inviteeKey));
	}

	@Test
	public void anAnswerFromAClockAheadOfTheCreatorsIsTaken()
			throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId invitee = node.addContact(inviteeKey);
		node.manager.inviteContactToGroup(groupId, invitee, inviteeKey,
				"Invitee");
		node.now += MINUTE;

		accept(node, invitee, node.now + 2 * HOUR);

		assertTrue("an answer from a clock two hours ahead was refused",
				node.isMember(groupId, inviteeKey));
	}

	@Test
	public void anAnswerSignedAfterTheInviteRanOutIsRefused()
			throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId invitee = node.addContact(inviteeKey);
		node.manager.inviteContactToGroup(groupId, invitee, inviteeKey,
				"Invitee");
		node.now += 9 * DAY;

		accept(node, invitee, node.now);

		assertFalse(node.isMember(groupId, inviteeKey));
		assertEquals("the invite is still listed while a timely answer may"
				+ " arrive", 1, node.manager.getSentInvites(groupId).size());
	}

	@Test
	public void anInviteIsDroppedWhenTheGraceHasPassed() throws Exception {
		GroupTrTestNode node = creatorDevice();
		ContactId invitee = node.addContact(inviteeKey);
		long sent = node.now;
		node.manager.inviteContactToGroup(groupId, invitee, inviteeKey,
				"Invitee");
		node.now = sent + 15 * DAY;

		accept(node, invitee, sent + 6 * DAY);

		assertFalse(node.isMember(groupId, inviteeKey));
		assertTrue(node.manager.getSentInvites(groupId).isEmpty());
	}

	@Test
	public void aGroupLeftByARefusedAnswerCanBeOfferedAgainAndIsReplaced()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(inviteeKey);
		ContactId fromCreator = node.addContact(creatorKey);
		node.putPendingOffer(groupId, fromCreator, creatorKey, node.now);
		node.manager.acceptInvite(groupId);
		GroupTrState phantom = node.group(groupId);
		assertEquals(0L, phantom.getEpoch());
		assertTrue(GroupTrManagerImpl.inviteOfferAdmissible(creatorKey,
				creatorKey, phantom, inviteeKey, false, groupId, groupId));
		node.now += DAY;
		node.putPendingOffer(groupId, fromCreator, creatorKey, node.now);
		node.sent.clear();

		node.manager.acceptInvite(groupId);

		assertEquals("the new offer was not answered, so the invitee stays in"
				+ " a group the creator never confirmed", 1, accepts(node));
		GroupTrState replaced = node.group(groupId);
		assertEquals(0L, replaced.getEpoch());
		assertTrue(node.isMember(groupId, inviteeKey));
		assertTrue(node.manager.getPendingInvites().isEmpty());
	}

	@Test
	public void aGroupTheCreatorConfirmedIsNeitherOfferedAgainNorReplaced()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(inviteeKey);
		ContactId fromCreator = node.addContact(creatorKey);
		node.putPendingOffer(groupId, fromCreator, creatorKey, node.now);
		node.manager.acceptInvite(groupId);
		node.added(fromCreator, groupId, inviteeKey, 1L);
		GroupTrState confirmed = node.group(groupId);
		assertEquals(1L, confirmed.getEpoch());
		assertFalse(GroupTrManagerImpl.inviteOfferAdmissible(creatorKey,
				creatorKey, confirmed, inviteeKey, false, groupId, groupId));
		node.putPendingOffer(groupId, fromCreator, creatorKey, node.now);
		node.sent.clear();

		node.manager.acceptInvite(groupId);

		assertEquals(0, accepts(node));
		assertEquals(1L, node.group(groupId).getEpoch());
	}

	@Test
	public void onlyAGroupWithNothingFromTheCreatorIsInBootstrapState() {
		assertTrue(GroupTrManagerImpl.inBootstrapState(
				group(0L, members(creator(creatorKey),
						new GroupTrMember(inviteeKey, "Me", 0L, 0L))),
				inviteeKey));
		assertFalse("an epoch moved by the creator",
				GroupTrManagerImpl.inBootstrapState(
						group(1L, members(creator(creatorKey),
								new GroupTrMember(inviteeKey, "Me", 0L, 0L))),
						inviteeKey));
		assertFalse("another member known",
				GroupTrManagerImpl.inBootstrapState(
						group(0L, members(creator(creatorKey),
								new GroupTrMember(inviteeKey, "Me", 0L, 0L),
								new GroupTrMember(otherKey, "Other", 0L, 0L))),
						inviteeKey));
		assertFalse("joined at a later epoch",
				GroupTrManagerImpl.inBootstrapState(
						group(0L, members(creator(creatorKey),
								new GroupTrMember(inviteeKey, "Me", 0L, 2L))),
						inviteeKey));
		assertFalse("not a member at all",
				GroupTrManagerImpl.inBootstrapState(
						group(0L, members(creator(creatorKey),
								new GroupTrMember(otherKey, "Other", 0L, 0L))),
						inviteeKey));
	}

	@Test
	public void anInviteSentByAnEarlierVersionIsDatedBeforeAnyAnswerArrives()
			throws Exception {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey, true);
		node.now = 1_700_000_000_000L;
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey));
		ContactId invitee = node.addContact(inviteeKey);
		node.putSentInvite(groupId, invitee, inviteeKey, null);

		node.manager.onDatabaseOpened(new Transaction(null, false));
		assertEquals("an undated invite is not listed while the conversion"
				+ " is on its way", 1,
				node.manager.getSentInvites(groupId).size());
		accept(node, invitee, node.now);

		assertTrue("an answer to an invite sent by an earlier version was"
				+ " refused while the conversion was on its way",
				node.isMember(groupId, inviteeKey));
		node.runDeferred();
	}

	private static int accepts(GroupTrTestNode node) throws Exception {
		int n = 0;
		for (GroupTrTestNode.Sent s : node.sent) {
			if (s.body.getLong(0) == 43L) n++;
		}
		return n;
	}

	private void accept(GroupTrTestNode node, ContactId from, long ts) {
		node.manager.eventOccurred(new GroupTrInviteResponseReceivedEvent(
				from, groupId, ts, SIG,
				GroupTrInviteResponseReceivedEvent.Kind.ACCEPT));
	}

	private GroupTrTestNode creatorDevice() {
		GroupTrTestNode node = new GroupTrTestNode(creatorKey);
		node.putGroup(groupId, creatorKey, EPOCH, creator(creatorKey));
		node.addContact(otherKey);
		return node;
	}

	private GroupTrState group(long epoch, List<GroupTrMember> members) {
		return new GroupTrState(groupId, "g", new byte[32], creatorKey,
				"Creator", 0L, epoch, false, members);
	}

	private static List<GroupTrMember> members(GroupTrMember... ms) {
		return new ArrayList<>(Arrays.asList(ms));
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}
