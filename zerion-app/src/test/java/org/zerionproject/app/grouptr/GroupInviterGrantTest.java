package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.grouptr.MemberInviteAvailability;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.versioning.ClientVersion;
import org.zerionproject.core.api.versioning.event.ClientVersionUpdatedEvent;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.grouptr.GroupMemberInviteFlowTest.assertUnavailable;

public class GroupInviterGrantTest {

	private final InviteTestNet net = new InviteTestNet();
	private final InviteTestNet.Node creator = net.node("Carol", 1);
	private final InviteTestNet.Node member = net.node("Mia", 2);
	private final InviteTestNet.Node invitee = net.node("Xavi", 3);
	private final InviteTestNet.Node other = net.node("Olga", 4);
	private byte[] groupId;

	@Before
	public void setUp() throws Exception {
		net.connect(creator, member);
		net.connect(creator, other);
		net.connect(member, other);
		net.connect(member, invitee);
		groupId = creator.manager.createGroup("Club").getGroupId();
		join(member);
		join(other);
	}

	@Test
	public void theAppAnnouncesTheVersionThatCarriesTheNewRecords() {
		assertTrue(MessagingManager.GROUP_INVITER_GRANTS_MIN_VERSION
				> MessagingManager.GROUP_ACTIONS_MIN_VERSION);
		assertTrue(MessagingManager.GROUP_INCOGNITO_MIN_VERSION
				> MessagingManager.GROUP_ACTIONS_MIN_VERSION);
		assertTrue(MessagingManager.MINOR_VERSION
				>= MessagingManager.GROUP_INVITER_GRANTS_MIN_VERSION);
		assertTrue(MessagingManager.MINOR_VERSION
				>= MessagingManager.GROUP_INCOGNITO_MIN_VERSION);
	}

	@Test
	public void theCreatorGrantsOneMemberTheRightToAddMembers()
			throws Exception {
		assertEquals(0, creator.manager.setInviterGrant(groupId, member.pub,
				true));
		net.pump();

		assertTrue(creator.manager.hasInviterGrant(groupId, member.pub));
		assertTrue(member.manager.hasInviterGrant(groupId, member.pub));
		assertEquals(0L, member.manager.getGroupPermissions(groupId));
		assertEquals(MemberInviteAvailability.ALLOWED,
				member.manager.getMemberInviteAvailability(groupId));
		assertEquals("only the member it names is sent the grant", 1,
				sentOfType(creator, MessageTypes.GROUP_INVITER_GRANT).size());
		assertFalse(other.manager.hasInviterGrant(groupId, member.pub));
		assertEquals(MemberInviteAvailability.NOT_ENABLED,
				other.manager.getMemberInviteAvailability(groupId));
	}

	@Test
	public void aGrantedMemberInvitesWithTheGrantAsProof() throws Exception {
		creator.manager.setInviterGrant(groupId, member.pub, true);
		net.pump();

		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		net.pump();
		assertTrue(sentOfType(member,
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER).isEmpty());
		assertEquals(1, sentOfType(member,
				MessageTypes.GROUPTR_GRANTED_INVITE_OFFER).size());
		invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(member));
		net.pump();

		assertTrue(creator.manager.isMember(groupId, invitee.pub));
		assertTrue(invitee.manager.isMember(groupId, invitee.pub));
		assertFalse(invitee.manager.isWaitingForAdmin(groupId));
	}

	@Test
	public void theCreatorsPhoneRefusesAJoinRequestFromAMemberWithoutTheRight()
			throws Exception {
		creator.manager.setInviterGrant(groupId, member.pub, true);
		net.pump();
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		net.pump();
		invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(member));
		net.pump(p -> p.to == creator
				&& p.type() == MessageTypes.GROUPTR_JOIN_REQUEST);
		InviteTestNet.Packet request = net.heldPackets().get(0);

		creator.manager.setInviterGrant(groupId, member.pub, false);
		assertFalse("the revocation takes effect on the creator's phone at once",
				creator.manager.hasInviterGrant(groupId, member.pub));
		creator.receive(request);

		assertFalse(creator.manager.isMember(groupId, invitee.pub));
		net.pump();
		assertFalse("the member was told",
				member.manager.hasInviterGrant(groupId, member.pub));
		assertEquals(MemberInviteAvailability.NOT_ENABLED,
				member.manager.getMemberInviteAvailability(groupId));
	}

	@Test
	public void aMemberWithoutAGrantOrTheGroupSwitchIsRefused()
			throws Exception {
		creator.manager.setInviterGrant(groupId, member.pub, true);
		net.pump();
		net.connect(other, invitee);

		assertUnavailable(() -> other.manager.inviteContactAsMember(groupId,
				other.contactIdOf(invitee)));
		InviteTestNet.Packet forged = joinRequestFrom(other);
		creator.receive(forged);
		assertFalse(creator.manager.isMember(groupId, invitee.pub));
	}

	@Test
	public void theInviteeRefusesAGrantedOfferWhoseGrantNamesAnotherMember()
			throws Exception {
		creator.manager.setInviterGrant(groupId, member.pub, true);
		net.pump();
		net.connect(other, invitee);
		byte[] grantSig = InviteTestNet.sign(creator,
				GroupTrMemberInvites.LABEL_INVITER_GRANT,
				GroupTrMemberInvites.grantSignedInput(groupId, member.pub, 1L,
						net.now));
		InviteTestNet.Packet offer = grantedOffer(other, 1L, net.now,
				grantSig);

		invitee.receive(offer);

		try {
			invitee.manager.acceptMemberInvite(groupId,
					invitee.contactIdOf(other));
			fail("an offer proven by another member's grant was taken");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.MEMBER_INVITES_UNAVAILABLE,
					e.getReason());
		}
	}

	@Test
	public void aStaleGrantIsNotUsed() throws Exception {
		creator.manager.setInviterGrant(groupId, member.pub, true);
		net.pump();
		net.now += GroupTrMemberInvites.PERMISSIONS_MAX_AGE_MS + 1L;

		assertUnavailable(() -> member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee)));
	}

	@Test
	public void thePostingCreatorRenewsADueGrant() throws Exception {
		creator.manager.setInviterGrant(groupId, member.pub, true);
		net.pump();
		int sent = sentOfType(creator, MessageTypes.GROUP_INVITER_GRANT)
				.size();
		net.now += GroupTrMemberInvites.PERMISSIONS_REFRESH_MS;

		creator.manager.sendGroupPost(groupId, new byte[] {1}, 0L);
		net.pump();

		assertEquals(sent + 1, sentOfType(creator,
				MessageTypes.GROUP_INVITER_GRANT).size());
		net.now += GroupTrMemberInvites.PERMISSIONS_MAX_AGE_MS
				- GroupTrMemberInvites.PERMISSIONS_REFRESH_MS + 1000L;
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		assertEquals(1, sentOfType(member,
				MessageTypes.GROUPTR_GRANTED_INVITE_OFFER).size());
	}

	@Test
	public void grantsGoOnlyToMembersOnVersionElevenOrLater()
			throws Exception {
		int older = MessagingManager.GROUP_INVITER_GRANTS_MIN_VERSION - 1;
		creator.setVersion(member, older);
		assertFalse(creator.manager.canReceiveInviterGrant(groupId,
				member.pub));

		assertEquals(1, creator.manager.setInviterGrant(groupId, member.pub,
				true));
		net.pump();
		assertTrue(sentOfType(creator, MessageTypes.GROUP_INVITER_GRANT)
				.isEmpty());

		creator.setVersion(member,
				MessagingManager.GROUP_INVITER_GRANTS_MIN_VERSION);
		creator.manager.eventOccurred(new ClientVersionUpdatedEvent(
				creator.contactIdOf(member), new ClientVersion(
				MessagingManager.CLIENT_ID, MessagingManager.MAJOR_VERSION,
				MessagingManager.GROUP_INVITER_GRANTS_MIN_VERSION)));
		net.pump();
		assertTrue(member.manager.hasInviterGrant(groupId, member.pub));
	}

	@Test
	public void aGrantedMemberOnlyInvitesContactsOnVersionEleven()
			throws Exception {
		creator.manager.setInviterGrant(groupId, member.pub, true);
		net.pump();
		member.setVersion(invitee,
				MessagingManager.GROUP_INVITER_GRANTS_MIN_VERSION - 1);

		assertTrue(member.manager.canReceiveMemberInvite(
				member.contactIdOf(invitee)));
		assertFalse(member.manager.canReceiveMemberInvite(groupId,
				member.contactIdOf(invitee)));
		assertUnavailable(() -> member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee)));

		creator.manager.setMembersMayInvite(groupId, true);
		net.pump();
		assertTrue("the group switch keeps working for version 10",
				member.manager.canReceiveMemberInvite(groupId,
						member.contactIdOf(invitee)));
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		assertEquals(1, sentOfType(member,
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER).size());
	}

	@Test
	public void onlyTheCreatorsSignedGrantForThisDeviceIsTaken()
			throws Exception {
		long ts = net.now + 1000L;
		member.receive(grant(other, creator, member.pub, 1L, ts, true));
		assertFalse("the sender is not the creator",
				member.manager.hasInviterGrant(groupId, member.pub));
		member.receive(grant(creator, other, member.pub, 1L, ts, true));
		assertFalse("the signer is not the creator",
				member.manager.hasInviterGrant(groupId, member.pub));
		member.receive(grant(creator, creator, member.pub, 1L, ts, false));
		assertFalse("a forged signature",
				member.manager.hasInviterGrant(groupId, member.pub));
		member.receive(grant(creator, creator, other.pub, 1L, ts, true));
		assertFalse("a grant for another member",
				member.manager.hasInviterGrant(groupId, other.pub));
		member.receive(grant(creator, creator, member.pub, 1L, ts, true));
		assertTrue(member.manager.hasInviterGrant(groupId, member.pub));
		member.receive(grant(creator, creator, member.pub, 0L, ts, true));
		assertTrue("not newer than the held grant",
				member.manager.hasInviterGrant(groupId, member.pub));
		member.receive(grant(creator, creator, member.pub, 0L, ts + 1, true));
		assertFalse(member.manager.hasInviterGrant(groupId, member.pub));
	}

	@Test
	public void onlyTheCreatorGrantsAndOnlyToListedMembers()
			throws Exception {
		try {
			member.manager.setInviterGrant(groupId, other.pub, true);
			fail("a member granted a right");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.NOT_CREATOR,
					e.getReason());
		}
		try {
			creator.manager.setInviterGrant(groupId, invitee.pub, true);
			fail("a right was granted to someone who is not a member");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.CONTACT_NOT_FOUND,
					e.getReason());
		}
	}

	@Test
	public void aRemovedMemberLosesItsGrant() throws Exception {
		creator.manager.setInviterGrant(groupId, member.pub, true);
		net.pump();
		creator.manager.removeMember(groupId, member.pub);
		net.pump();
		assertFalse(creator.manager.hasInviterGrant(groupId, member.pub));

		join(member);
		assertFalse("a member added again starts without the right",
				creator.manager.hasInviterGrant(groupId, member.pub));
	}

	@Test
	public void aMemberWithTheRightToAddCannotRemoveAnyone()
			throws Exception {
		creator.manager.setInviterGrant(groupId, member.pub, true);
		net.pump();
		long otherEpoch = other.manager.getEpoch(groupId);

		try {
			member.manager.removeMember(groupId, other.pub);
			fail("a member removed someone");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.NOT_CREATOR,
					e.getReason());
		}
		assertTrue(sentOfType(member, MessageTypes.GROUP_MEMBER_REMOVED)
				.isEmpty());

		int from = (int) creator.manager.getEpoch(groupId);
		long ts = net.now;
		byte[] memberRemovesOther = InviteTestNet.sign(member,
				"org.zerionproject/GROUP_MEMBERSHIP",
				InviteTestNet.removedSignedInput(groupId, other.pub, from,
						from + 1, ts));
		creator.receive(net.packet(member, creator, BdfList.of(
				(long) MessageTypes.GROUP_MEMBER_REMOVED, groupId, other.pub,
				(long) from, (long) from + 1, ts, memberRemovesOther)));
		other.receive(net.packet(member, other, BdfList.of(
				(long) MessageTypes.GROUP_MEMBER_REMOVED, groupId, other.pub,
				(long) from, (long) from + 1, ts, memberRemovesOther)));
		byte[] creatorSignedButSentByMember = InviteTestNet.sign(creator,
				"org.zerionproject/GROUP_MEMBERSHIP",
				InviteTestNet.removedSignedInput(groupId, other.pub, from,
						from + 1, ts));
		other.receive(net.packet(member, other, BdfList.of(
				(long) MessageTypes.GROUP_MEMBER_REMOVED, groupId, other.pub,
				(long) from, (long) from + 1, ts,
				creatorSignedButSentByMember)));
		BdfList list = BdfList.of(BdfList.of(creator.pub, "", 0L, 0L, 2L),
				BdfList.of(member.pub, "", 0L, 1L, 0L));
		java.nio.ByteBuffer canonical = java.nio.ByteBuffer.allocate(74);
		canonical.put(creator.pub).put((byte) 2).putInt(0);
		canonical.put(member.pub).put((byte) 0).putInt(1);
		byte[] snapshotSig = InviteTestNet.sign(member,
				"org.zerionproject/GROUP_MEMBER_LIST_SNAPSHOT",
				InviteTestNet.snapshotSignedInput(groupId, from + 5, ts,
						canonical.array()));
		other.receive(net.packet(member, other, BdfList.of(
				(long) MessageTypes.GROUP_MEMBER_LIST_SNAPSHOT, groupId,
				(long) from + 5, ts, list, snapshotSig)));
		net.pump();

		assertTrue(creator.manager.isMember(groupId, other.pub));
		assertTrue(other.manager.isMember(groupId, other.pub));
		assertTrue(member.manager.isMember(groupId, other.pub));
		assertEquals(otherEpoch, other.manager.getEpoch(groupId));
	}

	@Test
	public void aRemovalByTheCreatorStillWorks() throws Exception {
		creator.manager.removeMember(groupId, other.pub);
		net.pump();

		assertFalse(creator.manager.isMember(groupId, other.pub));
		assertFalse(member.manager.isMember(groupId, other.pub));
		assertEquals(null, other.manager.getGroup(groupId));
	}

	private void join(InviteTestNet.Node n) throws Exception {
		creator.manager.inviteContactToGroup(groupId, creator.contactIdOf(n),
				n.pub, n.name);
		net.pump();
		n.manager.acceptInvite(groupId);
		net.pump();
		assertTrue(creator.manager.isMember(groupId, n.pub));
	}

	private InviteTestNet.Packet grant(InviteTestNet.Node from,
			InviteTestNet.Node signer, byte[] memberPub, long flags, long ts,
			boolean validSig) {
		byte[] sig = InviteTestNet.sign(signer,
				GroupTrMemberInvites.LABEL_INVITER_GRANT,
				GroupTrMemberInvites.grantSignedInput(groupId, memberPub,
						flags, ts));
		if (!validSig) sig[sig.length - 1] ^= 1;
		return net.packet(from, from, BdfList.of(
				(long) MessageTypes.GROUP_INVITER_GRANT, groupId, memberPub,
				flags, ts, sig));
	}

	private InviteTestNet.Packet grantedOffer(InviteTestNet.Node inviter,
			long flags, long grantTs, byte[] grantSig) throws Exception {
		org.zerionproject.app.api.grouptr.GroupTrState s =
				creator.manager.getGroup(groupId);
		long ts = net.now;
		byte[] signed = GroupTrMemberInvites.offerSignedInput(groupId,
				inviter.pub, invitee.pub, creator.pub, ts, s.getName(),
				s.getSalt(), s.getCreatorName(), creator.mlDsa, flags, grantTs,
				grantSig);
		byte[] sig = InviteTestNet.sign(inviter,
				GroupTrMemberInvites.LABEL_GRANTED_OFFER, signed);
		return net.packet(inviter, invitee, BdfList.of(
				(long) MessageTypes.GROUPTR_GRANTED_INVITE_OFFER, groupId,
				s.getName(), s.getSalt(), s.getCreatorName(), creator.pub,
				creator.mlDsa, inviter.pub, flags, grantTs, grantSig, ts,
				sig));
	}

	private InviteTestNet.Packet joinRequestFrom(InviteTestNet.Node inviter) {
		long ts = net.now;
		byte[] acceptSig = InviteTestNet.sign(invitee,
				GroupTrMemberInvites.LABEL_ACCEPT,
				GroupTrMemberInvites.responseSignedInput(groupId, invitee.pub,
						inviter.pub, creator.pub, ts, invitee.mlDsa));
		byte[] sig = InviteTestNet.sign(inviter,
				GroupTrMemberInvites.LABEL_JOIN_REQUEST,
				GroupTrMemberInvites.joinRequestSignedInput(groupId,
						inviter.pub, invitee.pub, ts, ts, invitee.name,
						invitee.mlDsa, acceptSig));
		return net.packet(inviter, creator, BdfList.of(
				(long) MessageTypes.GROUPTR_JOIN_REQUEST, groupId, invitee.pub,
				invitee.name, invitee.mlDsa, ts, acceptSig, ts, sig));
	}

	private static List<InviteTestNet.Packet> sentOfType(
			InviteTestNet.Node n, int type) {
		List<InviteTestNet.Packet> out = new ArrayList<>();
		for (InviteTestNet.Packet p : n.sent()) {
			if (p.type() == type) out.add(p);
		}
		return out;
	}
}
