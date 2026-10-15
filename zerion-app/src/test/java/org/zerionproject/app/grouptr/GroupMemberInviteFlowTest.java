package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberInviteAvailability;
import org.zerionproject.app.api.messaging.event.GroupTrPostAcceptedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrSelfRemovedEvent;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.Transaction;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.grouptr.GroupTrMemberInvites.DAY_MS;
import static org.zerionproject.app.grouptr.GroupTrMemberInvites.MAX_JOINS_PER_INVITER_PER_DAY;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupMemberInviteFlowTest {

	private final InviteTestNet net = new InviteTestNet();
	private final InviteTestNet.Node creator = net.node("Carol", 1);
	private final InviteTestNet.Node member = net.node("Mia", 2);
	private final InviteTestNet.Node invitee = net.node("Xavi", 3);
	private byte[] groupId;

	@Before
	public void setUp() throws Exception {
		net.connect(creator, member);
		net.connect(member, invitee);
		groupId = creator.manager.createGroup("Club").getGroupId();
		join(member);
	}

	@Test
	public void membersMayNotInviteUntilTheCreatorAllowsIt()
			throws Exception {
		assertEquals(0L, creator.manager.getGroupPermissions(groupId));
		assertEquals(0L, member.manager.getGroupPermissions(groupId));
		assertEquals(MemberInviteAvailability.NOT_ENABLED,
				member.manager.getMemberInviteAvailability(groupId));
		assertUnavailable(() -> member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee)));
		assertTrue(sentOfType(member, 49).isEmpty());
	}

	@Test
	public void aMembersInviteJoinsThroughTheCreatorsPhone()
			throws Exception {
		enable();
		assertEquals(MemberInviteAvailability.ALLOWED,
				member.manager.getMemberInviteAvailability(groupId));

		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		net.pump();
		invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(member));

		GroupTrState joining = invitee.manager.getGroup(groupId);
		assertNotNull(joining);
		assertEquals(0L, joining.getEpoch());
		assertEquals(3, joining.getMembers().size());
		assertTrue(invitee.manager.isWaitingForAdmin(groupId));
		try {
			invitee.manager.sendGroupPost(groupId, bytes("early"), 0L);
			fail("a joining member posted before the creator added it");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.NOT_A_MEMBER,
					e.getReason());
		}

		net.pump();

		assertTrue(creator.manager.isMember(groupId, invitee.pub));
		assertTrue(member.manager.isMember(groupId, invitee.pub));
		assertTrue(invitee.manager.isMember(groupId, invitee.pub));
		assertFalse(invitee.manager.isWaitingForAdmin(groupId));
		assertNull("the joining mark ends with the creator's snapshot",
				invitee.namespace(GroupTrConstants.SETTINGS_NS_PREFIX
						+ toHexString(groupId)).get(
						GroupTrMemberInvites.S_JOINING_VIA));
		assertEquals(creator.manager.getEpoch(groupId),
				invitee.manager.getEpoch(groupId));
		boolean screenTold = false;
		for (Object o : invitee.broadcasts) {
			if (o instanceof org.zerionproject.app.api.messaging.event
					.GroupTrLocalStateChangedEvent
					&& ((org.zerionproject.app.api.messaging.event
					.GroupTrLocalStateChangedEvent) o).getKind()
					== org.zerionproject.app.api.messaging.event
					.GroupTrLocalStateChangedEvent.Kind.UPDATED) {
				screenTold = true;
			}
		}
		assertTrue(screenTold);
		GroupTrMember added = memberEntry(creator, invitee.pub);
		assertArrayEquals("the creator keeps the invitee's ML-DSA key",
				invitee.mlDsa, added.getMlDsaPubKey());

		invitee.manager.sendGroupPost(groupId, bytes("hello"), 0L);
		List<InviteTestNet.Packet> posts = sentOfType(invitee, 32);
		assertEquals(1, posts.size());
		assertEquals(member, posts.get(0).to);
		net.pump();
		assertTrue(acceptedPostAt(member));
	}

	@Test
	public void theInviterForwardsAnExactCopyOfTheCreatorsSnapshot()
			throws Exception {
		enable();
		inviteAndAccept();
		net.pump();

		InviteTestNet.Packet fromCreator = null;
		InviteTestNet.Packet forwarded = null;
		for (InviteTestNet.Packet p : net.delivered) {
			if (p.type() != 41) continue;
			if (p.from == creator && p.to == member) fromCreator = p;
			if (p.from == member && p.to == invitee) forwarded = p;
		}
		assertNotNull(fromCreator);
		assertNotNull(forwarded);
		assertArrayEquals(fromCreator.bytes, forwarded.bytes);
		assertEquals(1, sentOfType(member, 41).size());
	}

	@Test
	public void anInviteeOutsideTheCreatorsContactsLearnsOfLaterMembers()
			throws Exception {
		enable();
		inviteAndAccept();
		net.pump();
		assertTrue(invitee.manager.isMember(groupId, invitee.pub));
		InviteTestNet.Node later = net.node("Yara", 5);
		net.connect(creator, later);

		join(later);
		net.pump();

		assertTrue(creator.manager.isMember(groupId, later.pub));
		assertTrue("the inviter forwarded the newer snapshot",
				invitee.manager.isMember(groupId, later.pub));
		assertEquals(creator.manager.getEpoch(groupId),
				invitee.manager.getEpoch(groupId));
	}

	@Test
	public void anInviteeOutsideTheCreatorsContactsLearnsItWasRemoved()
			throws Exception {
		enable();
		inviteAndAccept();
		net.pump();
		assertTrue(invitee.manager.isMember(groupId, invitee.pub));

		creator.manager.removeMember(groupId, invitee.pub);
		net.pump();

		assertFalse(member.manager.isMember(groupId, invitee.pub));
		assertNull("the removed invitee no longer holds the group",
				invitee.manager.getGroup(groupId));
		assertFalse(invitee.manager.isWaitingForAdmin(groupId));
		boolean told = false;
		for (Object o : invitee.broadcasts) {
			if (o instanceof GroupTrSelfRemovedEvent && Arrays.equals(
					((GroupTrSelfRemovedEvent) o).getGroupId(), groupId)) {
				told = true;
			}
		}
		assertTrue("the invitee is told it was removed", told);
		int forwardedBefore = sentOfType(member, 41).size();
		InviteTestNet.Node later = net.node("Yara", 5);
		net.connect(creator, later);
		join(later);
		net.pump();
		assertEquals("a removed invitee is not sent later snapshots",
				forwardedBefore, sentOfType(member, 41).size());
	}

	@Test
	public void anInviteeOutsideTheCreatorsContactsLearnsOfTheDissolution()
			throws Exception {
		enable();
		inviteAndAccept();
		net.pump();
		assertTrue(invitee.manager.isMember(groupId, invitee.pub));

		creator.manager.dissolveGroup(groupId);
		net.pump();

		assertEquals(1, sentOfType(member, 36).size());
		assertEquals(invitee, sentOfType(member, 36).get(0).to);
		GroupTrState seen = invitee.manager.getGroup(groupId);
		assertTrue("the invitee dropped the dissolved group",
				seen == null || seen.isDissolved());
	}

	@Test
	public void aJoinTheCreatorNeverConfirmsRunsOut() throws Exception {
		enable();
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		net.pump();
		invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(member));
		net.pump(p -> p.to == creator);
		assertTrue(invitee.manager.isWaitingForAdmin(groupId));

		net.now += GroupTrMemberInvites.JOIN_WINDOW_MS - 1000;
		assertTrue(invitee.manager.isWaitingForAdmin(groupId));
		assertNotNull(invitee.manager.getGroup(groupId));

		net.now += 2000;
		assertFalse(invitee.manager.isWaitingForAdmin(groupId));
		assertNull("a join that can no longer complete is removed",
				invitee.manager.getGroup(groupId));
	}

	@Test
	public void aMemberDoesNotInviteWithAStalePermission() throws Exception {
		enable();
		net.now += GroupTrMemberInvites.PERMISSIONS_MAX_AGE_MS + 1L;

		assertUnavailable(() -> member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee)));
		assertTrue(sentOfType(member, 49).isEmpty());
	}

	@Test
	public void aPostingCreatorKeepsThePermissionFresh() throws Exception {
		enable();
		int records = sentOfType(creator, 48).size();
		net.now += GroupTrMemberInvites.PERMISSIONS_REFRESH_MS;

		creator.manager.sendGroupPost(groupId, bytes("still here"), 0L);
		creator.manager.sendGroupPost(groupId, bytes("again"), 0L);
		net.pump();

		assertEquals("one renewal, sent to the one member", records + 1,
				sentOfType(creator, 48).size());
		net.now += GroupTrMemberInvites.PERMISSIONS_MAX_AGE_MS
				- GroupTrMemberInvites.PERMISSIONS_REFRESH_MS + DAY_MS;
		inviteAndAccept();
		net.pump();
		assertTrue(creator.manager.isMember(groupId, invitee.pub));
	}

	@Test
	public void theCreatorRenewsADuePermissionWhenItStarts()
			throws Exception {
		enable();
		int records = sentOfType(creator, 48).size();
		net.now += GroupTrMemberInvites.PERMISSIONS_REFRESH_MS;

		creator.manager.onDatabaseOpened(new Transaction(null, false));
		net.pump();

		assertEquals(records + 1, sentOfType(creator, 48).size());
		net.now += GroupTrMemberInvites.PERMISSIONS_MAX_AGE_MS
				- GroupTrMemberInvites.PERMISSIONS_REFRESH_MS + DAY_MS;
		inviteAndAccept();
		net.pump();
		assertTrue(creator.manager.isMember(groupId, invitee.pub));
	}

	@Test
	public void theCreatorAddsNobodyWhileTheSettingIsOff() throws Exception {
		enable();
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		net.pump();
		creator.manager.setMembersMayInvite(groupId, false);
		net.pump(p -> p.type() == 48);
		invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(member));
		net.pump(p -> p.type() == 48);

		assertEquals("the member did not learn the setting changed", 1,
				sentOfType(member, 52).size());
		assertFalse(creator.manager.isMember(groupId, invitee.pub));
		assertTrue(invitee.manager.isWaitingForAdmin(groupId));
	}

	@Test
	public void disablingTheSettingStopsLaterInvites() throws Exception {
		enable();
		inviteAndAccept();
		net.pump();
		assertTrue(creator.manager.isMember(groupId, invitee.pub));

		InviteTestNet.Node other = net.node("Yara", 4);
		net.connect(member, other);
		creator.manager.setMembersMayInvite(groupId, false);
		net.pump();

		assertEquals(0L, member.manager.getGroupPermissions(groupId));
		assertEquals(MemberInviteAvailability.NOT_ENABLED,
				member.manager.getMemberInviteAvailability(groupId));
		assertUnavailable(() -> member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(other)));
	}

	@Test
	public void aMemberThatDisabledItselfDoesNotForwardAnAcceptance()
			throws Exception {
		enable();
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		net.pump();
		creator.manager.setMembersMayInvite(groupId, false);
		net.pump();
		invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(member));
		net.pump();

		assertTrue(sentOfType(member, 52).isEmpty());
		assertFalse(creator.manager.isMember(groupId, invitee.pub));
	}

	@Test
	public void joinRequestsAreLimitedPerInvitingMemberAndDay()
			throws Exception {
		enable();
		int n = MAX_JOINS_PER_INVITER_PER_DAY + 1;
		InviteTestNet.Node[] people = new InviteTestNet.Node[n];
		for (int i = 0; i < n; i++) {
			people[i] = net.node("P" + i, 20 + i);
			net.connect(member, people[i]);
			member.manager.inviteContactAsMember(groupId,
					member.contactIdOf(people[i]));
		}
		net.pump();
		for (InviteTestNet.Node p : people) {
			p.manager.acceptMemberInvite(groupId,
					p.contactIdOf(member));
		}
		net.pump();

		int added = 0;
		for (InviteTestNet.Node p : people) {
			if (creator.manager.isMember(groupId, p.pub)) added++;
		}
		assertEquals(MAX_JOINS_PER_INVITER_PER_DAY, added);

		net.now += DAY_MS;
		InviteTestNet.Node later = net.node("Later", 60);
		net.connect(member, later);
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(later));
		net.pump();
		later.manager.acceptMemberInvite(groupId,
				later.contactIdOf(member));
		net.pump();
		assertTrue(creator.manager.isMember(groupId, later.pub));
	}

	@Test
	public void aReplayedJoinRequestDoesNotAddAnInviteeAgain()
			throws Exception {
		enable();
		inviteAndAccept();
		net.pump();
		InviteTestNet.Packet request = sentOfType(member, 52).get(0);
		creator.manager.removeMember(groupId, invitee.pub);
		assertFalse(creator.manager.isMember(groupId, invitee.pub));

		creator.receive(request);

		assertFalse(creator.manager.isMember(groupId, invitee.pub));
	}

	@Test
	public void onlyTheCreatorsSignedPermissionsAreTaken() throws Exception {
		InviteTestNet.Node other = net.node("Olga", 4);
		net.connect(member, other);
		net.connect(creator, other);
		join(other);
		long ts = net.now + 1000;

		other.receive(permissions(member, member, 1L, ts, true));
		assertEquals("a member is not the creator", 0L,
				other.manager.getGroupPermissions(groupId));

		other.receive(permissions(creator, creator, 1L, ts, false));
		assertEquals("a forged signature", 0L,
				other.manager.getGroupPermissions(groupId));

		other.receive(permissions(creator, creator, 1L, ts, true));
		assertEquals(1L, other.manager.getGroupPermissions(groupId));

		other.receive(permissions(creator, creator, 0L, ts, true));
		other.receive(permissions(creator, creator, 0L, ts - 1, true));
		assertEquals("not newer than the held record", 1L,
				other.manager.getGroupPermissions(groupId));

		other.receive(permissions(creator, creator, 0L, ts + 1, true));
		assertEquals(0L, other.manager.getGroupPermissions(groupId));
	}

	@Test
	public void unknownPermissionBitsAreKeptButOnlyTheFirstCounts()
			throws Exception {
		member.receive(permissions(creator, creator, 6L, net.now + 5,
				true));
		assertEquals(6L, member.manager.getGroupPermissions(groupId));
		assertEquals(MemberInviteAvailability.NOT_ENABLED,
				member.manager.getMemberInviteAvailability(groupId));
	}

	@Test
	public void aMemberAddedLaterIsSentTheSettingInForce() throws Exception {
		enable();
		InviteTestNet.Node other = net.node("Olga", 4);
		net.connect(creator, other);
		join(other);

		assertEquals(1L, other.manager.getGroupPermissions(groupId));
	}

	@Test
	public void membersOnAnOlderVersionAreSentTheSettingWhenTheyUpdate()
			throws Exception {
		creator.setVersion(member, 9);
		int skipped = creator.manager.setMembersMayInvite(groupId, true);
		net.pump();
		assertEquals(1, skipped);
		assertTrue(sentOfType(creator, 48).isEmpty());
		assertEquals(0L, member.manager.getGroupPermissions(groupId));

		creator.setVersion(member, 10);
		creator.manager.eventOccurred(new org.zerionproject.core.api
				.versioning.event.ClientVersionUpdatedEvent(
				creator.contactIdOf(member), new org.zerionproject.core.api
				.versioning.ClientVersion(org.zerionproject.app.api.messaging
				.MessagingManager.CLIENT_ID, 0, 10)));
		net.pump();
		assertEquals(1L, member.manager.getGroupPermissions(groupId));
	}

	@Test
	public void contactsOnAnOlderVersionCannotBeInvitedByMembers()
			throws Exception {
		enable();
		member.setVersion(invitee, 9);

		assertFalse(member.manager.canReceiveMemberInvite(
				member.contactIdOf(invitee)));
		assertUnavailable(() -> member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee)));
		assertTrue(sentOfType(member, 49).isEmpty());
	}

	@Test
	public void aCreatorOnAnOlderVersionDisablesMemberInvites()
			throws Exception {
		enable();
		member.setVersion(creator, 9);

		assertEquals(MemberInviteAvailability.CREATOR_ON_OLDER_VERSION,
				member.manager.getMemberInviteAvailability(groupId));
		assertUnavailable(() -> member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee)));
	}

	@Test
	public void aMemberWhoIsNotTheCreatorsContactCannotInvite()
			throws Exception {
		enable();
		member.forgetContact(creator);

		assertEquals(MemberInviteAvailability.CREATOR_NOT_A_CONTACT,
				member.manager.getMemberInviteAvailability(groupId));
		assertUnavailable(() -> member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee)));
		assertTrue(sentOfType(member, 49).isEmpty());
	}

	@Test
	public void anInviteeThatJoinedThroughAMemberCannotInviteFurther()
			throws Exception {
		enable();
		inviteAndAccept();
		net.pump();

		assertEquals(MemberInviteAvailability.NOT_ENABLED,
				invitee.manager.getMemberInviteAvailability(groupId));
	}

	@Test
	public void theCreatorInvitesThroughTheExistingOffer() throws Exception {
		enable();
		assertEquals(MemberInviteAvailability.UNAVAILABLE,
				creator.manager.getMemberInviteAvailability(groupId));
		InviteTestNet.Node other = net.node("Olga", 4);
		net.connect(creator, other);
		creator.manager.inviteContactToGroup(groupId,
				creator.contactIdOf(other), other.pub, other.name);
		net.pump();
		other.manager.declineInvite(groupId);
		net.pump();

		int offers = 0;
		for (InviteTestNet.Packet p : sentOfType(creator, 42)) {
			if (p.to == other) offers++;
		}
		assertEquals(1, offers);
		assertEquals(1, sentOfType(other, 44).size());
		assertFalse(creator.manager.isMember(groupId, other.pub));
		assertTrue(creator.manager.getSentInvites(groupId).isEmpty());
	}

	@Test
	public void aDeclineEndsTheMembersInvite() throws Exception {
		enable();
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		net.pump();
		invitee.manager.declineMemberInvite(groupId,
				invitee.contactIdOf(member));
		net.pump();

		assertEquals(1, sentOfType(invitee, 51).size());
		assertTrue(sentOfType(member, 52).isEmpty());
		assertTrue(member.namespace(GroupTrMemberInvites.nsOf(groupId))
				.keySet().stream().noneMatch(k -> k.startsWith(
						GroupTrMemberInvites.K_SENT_PREFIX)));
		assertNull(invitee.manager.getGroup(groupId));
	}

	@Test
	public void leavingTheGroupDeletesTheMemberInviteState()
			throws Exception {
		enable();
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		net.pump();
		assertFalse(member.namespace(GroupTrMemberInvites.nsOf(groupId))
				.isEmpty());

		member.manager.leaveGroup(groupId);
		net.pump();

		assertTrue(member.namespace(GroupTrMemberInvites.nsOf(groupId))
				.isEmpty());
	}

	private void inviteAndAccept() throws Exception {
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		net.pump();
		invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(member));
	}

	private void enable() throws Exception {
		creator.manager.setMembersMayInvite(groupId, true);
		net.pump();
		assertEquals(1L, member.manager.getGroupPermissions(groupId));
	}

	private void join(InviteTestNet.Node n) throws Exception {
		creator.manager.inviteContactToGroup(groupId, creator.contactIdOf(n),
				n.pub, n.name);
		net.pump();
		n.manager.acceptInvite(groupId);
		net.pump();
		assertTrue(creator.manager.isMember(groupId, n.pub));
		assertTrue(n.manager.isMember(groupId, n.pub));
	}

	private InviteTestNet.Packet permissions(InviteTestNet.Node from,
			InviteTestNet.Node signer, long flags, long ts,
			boolean validSig) {
		byte[] signed = GroupTrMemberInvites.permissionsSignedInput(groupId,
				flags, ts);
		byte[] sig = InviteTestNet.sign(signer,
				GroupTrMemberInvites.LABEL_PERMISSIONS, signed);
		if (!validSig) sig[sig.length - 1] ^= 1;
		return net.packet(from, from, BdfList.of(48L, groupId, flags, ts,
				sig));
	}

	private boolean acceptedPostAt(InviteTestNet.Node n) {
		for (Object o : n.broadcasts) {
			if (o instanceof GroupTrPostAcceptedEvent
					&& Arrays.equals(((GroupTrPostAcceptedEvent) o)
					.getGroupId(), groupId)) {
				return true;
			}
		}
		return false;
	}

	private GroupTrMember memberEntry(InviteTestNet.Node n, byte[] pub)
			throws Exception {
		GroupTrState s = n.manager.getGroup(groupId);
		assertNotNull(s);
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pub)) return m;
		}
		throw new AssertionError("not a member");
	}

	private static List<InviteTestNet.Packet> sentOfType(
			InviteTestNet.Node n, int type) {
		List<InviteTestNet.Packet> out = new java.util.ArrayList<>();
		for (InviteTestNet.Packet p : n.sent()) {
			if (p.type() == type) out.add(p);
		}
		return out;
	}

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}

	interface Call {
		void run() throws Exception;
	}

	static void assertUnavailable(Call c) throws Exception {
		try {
			c.run();
			fail("member invite was not refused");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.MEMBER_INVITES_UNAVAILABLE,
					e.getReason());
		}
	}
}
