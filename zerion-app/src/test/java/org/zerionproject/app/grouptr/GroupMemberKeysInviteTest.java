package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.data.BdfList;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.grouptr.GroupKeysKit.carries;
import static org.zerionproject.app.grouptr.GroupKeysKit.deliveredTo;
import static org.zerionproject.app.grouptr.GroupKeysKit.join;
import static org.zerionproject.app.grouptr.GroupKeysKit.key;
import static org.zerionproject.app.grouptr.GroupKeysKit.mlOf;
import static org.zerionproject.app.grouptr.GroupKeysKit.post;
import static org.zerionproject.app.grouptr.GroupKeysKit.remotePostsAccepted;
import static org.zerionproject.app.grouptr.GroupKeysKit.sentOfType;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupMemberKeysInviteTest {

	private final InviteTestNet net = new InviteTestNet();
	private final InviteTestNet.Node carol = net.node("Carol", 1);
	private final InviteTestNet.Node mia = net.node("Mia", 2);
	private final InviteTestNet.Node xavi = net.node("Xavi", 3);
	private final InviteTestNet.Node olga = net.node("Olga", 4);
	private byte[] groupId;

	@Before
	public void setUp() throws Exception {
		net.connect(carol, mia);
		net.connect(carol, olga);
		net.connect(mia, xavi);
		net.connect(mia, olga);
		groupId = carol.manager.createGroup("Club").getGroupId();
	}

	@Test
	public void theInvitationAndTheAnswerCarryOnlyGroupKeys()
			throws Exception {
		carol.manager.inviteContactToGroup(groupId, carol.contactIdOf(mia),
				mia.pub, mia.name);
		net.pump();
		BdfList offer = sentOfType(carol, MessageTypes.GROUPTR_INVITE_OFFER)
				.get(0).body;
		assertEquals(9, offer.size());
		assertArrayEquals(key(carol, groupId), offer.getRaw(5));
		assertArrayEquals(key(carol, groupId), offer.getList(8).getRaw(0));
		assertFalse(carries(offer, carol.pub));
		assertFalse(carries(offer, carol.mlDsa));
		assertFalse(carries(offer, mia.pub));

		mia.manager.acceptInvite(groupId);
		net.pump();
		BdfList accept = sentOfType(mia, MessageTypes.GROUPTR_INVITE_ACCEPT)
				.get(0).body;
		assertEquals(5, accept.size());
		byte[] miaKey = key(mia, groupId);
		assertArrayEquals(miaKey, accept.getList(4).getRaw(0));
		assertFalse(carries(accept, mia.pub));
		assertFalse(carries(accept, mia.mlDsa));
		assertTrue(carol.manager.isMember(groupId, miaKey));
		assertFalse(carol.manager.isMember(groupId, mia.pub));
		assertEquals(carol.contactIdOf(mia), carol.manager
				.getMemberContacts(groupId).get(toHexString(miaKey)));
		assertEquals(mia.contactIdOf(carol), mia.manager
				.getMemberContacts(groupId)
				.get(toHexString(key(carol, groupId))));
	}

	@Test
	public void membershipRecordsListGroupKeysOnly() throws Exception {
		join(net, carol, groupId, mia);
		join(net, carol, groupId, olga);
		carol.manager.sendMemberListSnapshot(groupId);
		net.pump();
		for (InviteTestNet.Packet p : carol.sent()) {
			if (p.type() != MessageTypes.GROUP_MEMBER_ADDED
					&& p.type() != MessageTypes.GROUP_MEMBER_LIST_SNAPSHOT) {
				continue;
			}
			for (InviteTestNet.Node n : new InviteTestNet.Node[] {carol, mia,
					olga}) {
				assertFalse(carries(p.body, n.pub));
			}
		}
		BdfList added = sentOfType(carol, MessageTypes.GROUP_MEMBER_ADDED)
				.get(1).body;
		assertArrayEquals(key(olga, groupId), added.getRaw(2));
		assertTrue(mia.manager.isMember(groupId, key(olga, groupId)));
		assertTrue(olga.manager.isMember(groupId, key(mia, groupId)));
		assertEquals(carol.manager.getEpoch(groupId),
				olga.manager.getEpoch(groupId));

		mia.manager.sendGroupPost(groupId, new byte[] {1}, 0L);
		net.pump();
		assertEquals("members who are contacts exchanged their keys", 1,
				remotePostsAccepted(olga, groupId));
	}

	@Test
	public void onlyTheCreatorsGroupKeyCanRemoveAMember() throws Exception {
		join(net, carol, groupId, mia);
		join(net, carol, groupId, olga);
		byte[] miaKey = key(mia, groupId);
		byte[] olgaKey = key(olga, groupId);
		long epoch = olga.manager.getEpoch(groupId);
		long ts = net.now;
		byte[] signed = InviteTestNet.removedSignedInput(groupId, olgaKey,
				(int) epoch, (int) epoch + 1, ts);
		BdfList forged = BdfList.of(34L, groupId, olgaKey, epoch, epoch + 1,
				ts, GroupKeysKit.signAs(miaKey,
						"org.zerionproject/GROUP_MEMBERSHIP", signed));
		olga.receive(net.packet(mia, olga, forged));
		assertTrue(olga.manager.isMember(groupId, olgaKey));

		try {
			mia.manager.removeMember(groupId, olgaKey);
			fail("a member removed another member");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.NOT_CREATOR,
					e.getReason());
		}
		carol.manager.removeMember(groupId, miaKey);
		net.pump();
		assertFalse(olga.manager.isMember(groupId, miaKey));
		assertTrue(mia.manager.getGroup(groupId) == null);
	}

	@Test
	public void aMembersInviteNeverShowsTheInviteeToTheCreator()
			throws Exception {
		join(net, carol, groupId, mia);
		carol.manager.setMembersMayInvite(groupId, true);
		net.pump();
		mia.manager.inviteContactAsMember(groupId, mia.contactIdOf(xavi));
		net.pump();
		BdfList offer = sentOfType(mia,
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER).get(0).body;
		assertEquals(14, offer.size());
		assertArrayEquals(key(carol, groupId), offer.getRaw(5));
		assertArrayEquals(key(mia, groupId), offer.getRaw(7));
		assertFalse(carries(offer, mia.pub));
		assertFalse(carries(offer, carol.pub));
		assertTrue(xavi.manager.canAcceptIncognito(groupId, true));

		xavi.manager.acceptMemberInvite(groupId, xavi.contactIdOf(mia));
		net.pump();
		byte[] xaviKey = key(xavi, groupId);
		assertFalse(Arrays.equals(xaviKey, xavi.pub));
		assertTrue(carol.manager.isMember(groupId, xaviKey));
		assertTrue(xavi.manager.isMember(groupId, xaviKey));
		assertFalse(xavi.manager.isWaitingForAdmin(groupId));
		BdfList request = sentOfType(mia, MessageTypes.GROUPTR_JOIN_REQUEST)
				.get(0).body;
		assertArrayEquals(xaviKey, request.getRaw(2));
		for (InviteTestNet.Packet p : deliveredTo(net, carol)) {
			assertFalse("type " + p.type() + " shows Xavi to the creator",
					carries(p.body, xavi.pub));
			assertFalse(carries(p.body, xavi.mlDsa));
			assertFalse(carries(p.body, mia.pub));
		}
		GroupTrState s = carol.manager.getGroup(groupId);
		assertNotNull(s);
		for (GroupTrMember m : s.getMembers()) {
			assertFalse(Arrays.equals(m.getPubKey(), xavi.pub));
		}
		assertEquals(xavi.contactIdOf(mia), xavi.manager
				.getMemberContacts(groupId)
				.get(toHexString(key(mia, groupId))));

		mia.manager.sendGroupPost(groupId, new byte[] {2}, 0L);
		net.pump();
		assertEquals(1, remotePostsAccepted(xavi, groupId));
	}

	@Test
	public void aGrantedMemberInvitesWithItsGroupKey() throws Exception {
		join(net, carol, groupId, mia);
		byte[] miaKey = key(mia, groupId);
		assertTrue(carol.manager.canReceiveInviterGrant(groupId, miaKey));
		assertEquals(0, carol.manager.setInviterGrant(groupId, miaKey, true));
		net.pump();
		assertTrue(mia.manager.hasInviterGrant(groupId, miaKey));
		BdfList grant = sentOfType(carol, MessageTypes.GROUP_INVITER_GRANT)
				.get(0).body;
		assertArrayEquals(miaKey, grant.getRaw(2));

		mia.manager.inviteContactAsMember(groupId, mia.contactIdOf(xavi));
		net.pump();
		assertEquals(1, sentOfType(mia,
				MessageTypes.GROUPTR_GRANTED_INVITE_OFFER).size());
		xavi.manager.acceptMemberInvite(groupId, xavi.contactIdOf(mia));
		net.pump();
		assertTrue(carol.manager.isMember(groupId, key(xavi, groupId)));
	}

	@Test
	public void aRemovedMemberCannotUseItsOldAuthorisation()
			throws Exception {
		join(net, carol, groupId, mia);
		join(net, carol, groupId, olga);
		carol.manager.setMembersMayInvite(groupId, true);
		net.pump();
		mia.manager.inviteContactAsMember(groupId, mia.contactIdOf(xavi));
		net.pump();
		BdfList oldOffer = sentOfType(mia,
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER).get(0).body;
		xavi.manager.acceptMemberInvite(groupId, xavi.contactIdOf(mia));
		net.pump();
		byte[] xaviKey = key(xavi, groupId);
		BdfList oldRequest = sentOfType(mia,
				MessageTypes.GROUPTR_JOIN_REQUEST).get(0).body;
		assertTrue(carol.manager.isMember(groupId, xaviKey));

		xavi.receive(net.packet(mia, xavi, oldOffer));
		assertFalse(xavi.manager.invitationStillOpen(
				new org.zerionproject.core.api.db.Transaction(null, true),
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER, groupId));

		carol.manager.removeMember(groupId, xaviKey);
		net.pump();
		assertFalse(carol.manager.isMember(groupId, xaviKey));
		carol.receive(net.packet(mia, carol, oldRequest));
		net.pump();
		assertFalse("a replayed join request re-added a removed key",
				carol.manager.isMember(groupId, xaviKey));

		try {
			mia.manager.inviteContactAsMember(groupId,
					mia.contactIdOf(xavi));
			fail("a member invited a contact the creator removed");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason
					.MEMBER_INVITES_UNAVAILABLE, e.getReason());
		}

		byte[] miaKey = key(mia, groupId);
		carol.manager.removeMember(groupId, miaKey);
		net.pump();
		long epoch = olga.manager.getEpoch(groupId);
		int before = remotePostsAccepted(olga, groupId);
		olga.receive(net.packet(mia, olga, post(groupId, epoch, miaKey,
				miaKey, mlOf(miaKey), new byte[] {3}, net.now)));
		olga.receive(net.packet(mia, olga, post(groupId, epoch - 1, miaKey,
				miaKey, mlOf(miaKey), new byte[] {4}, net.now)));
		assertEquals(before, remotePostsAccepted(olga, groupId));
	}

	@Test
	public void declinesAndOlderInvitationsStayPairwise() throws Exception {
		carol.manager.inviteContactToGroup(groupId, carol.contactIdOf(mia),
				mia.pub, mia.name);
		net.pump();
		mia.manager.declineInvite(groupId);
		net.pump();
		assertTrue(carol.manager.getSentInvites(groupId).isEmpty());
		assertFalse(carol.manager.isMember(groupId, mia.pub));
		List<InviteTestNet.Packet> declines = sentOfType(mia,
				MessageTypes.GROUPTR_INVITE_DECLINE);
		assertEquals(1, declines.size());
		assertEquals(carol, declines.get(0).to);
	}
}
