package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.core.api.data.BdfList;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.app.grouptr.GroupMemberInviteFlowTest.assertUnavailable;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupMemberInviteOfferTest {

	private static final long DAY = 24L * 60L * 60L * 1000L;

	private final InviteTestNet net = new InviteTestNet();
	private final InviteTestNet.Node creator = net.node("Carol", 1);
	private final InviteTestNet.Node member = net.node("Mia", 2);
	private final InviteTestNet.Node invitee = net.node("Xavi", 3);
	private final InviteTestNet.Node other = net.node("Olga", 4);
	private byte[] groupId;
	private BdfList offer;

	@Before
	public void setUp() throws Exception {
		net.connect(creator, member);
		net.connect(creator, other);
		net.connect(member, invitee);
		net.connect(other, invitee);
		groupId = creator.manager.createGroup("Club").getGroupId();
		join(member);
		join(other);
		creator.manager.setMembersMayInvite(groupId, true);
		net.pump();
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		net.pump(p -> p.type() == 49);
		assertEquals(1, net.heldPackets().size());
		offer = net.heldPackets().get(0).body;
	}

	@Test
	public void theOfferCarriesWhatTheInviteeNeedsToJoin() throws Exception {
		assertEquals(13, offer.size());
		assertEquals(49L, (long) offer.getLong(0));
		assertArrayEquals(groupId, offer.getRaw(1));
		assertEquals("Club", offer.getString(2));
		assertArrayEquals(creator.pub, offer.getRaw(5));
		assertArrayEquals(creator.mlDsa, offer.getRaw(6));
		assertArrayEquals(member.pub, offer.getRaw(7));
		assertEquals(1L, (long) offer.getLong(8));
	}

	@Test
	public void aGenuineOfferIsAdmittedAndAcceptedIntoAJoiningState()
			throws Exception {
		deliver(member, offer);
		invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(member));

		GroupTrState s = invitee.manager.getGroup(groupId);
		assertNotNull(s);
		assertEquals(0L, s.getEpoch());
		assertEquals(3, s.getMembers().size());
		assertArrayEquals(creator.mlDsa, s.getMembers().get(0)
				.getMlDsaPubKey());
		assertTrue(invitee.manager.isWaitingForAdmin(groupId));
	}

	@Test
	public void anOfferDeliveredByAnyoneButTheInviterIsRefused()
			throws Exception {
		deliver(other, offer);
		assertNotAdmitted();
	}

	@Test
	public void anOfferWithABadSignatureIsRefused() throws Exception {
		byte[] sig = offer.getRaw(12).clone();
		sig[0] ^= 1;
		deliver(member, with(12, sig, false));
		assertNotAdmitted();
	}

	@Test
	public void anOfferWhoseGroupIdDoesNotDeriveIsRefused() throws Exception {
		byte[] otherGroup = getRandomId();
		BdfList b = with(1, otherGroup, false);
		b.set(10, InviteTestNet.sign(creator,
				GroupTrMemberInvites.LABEL_PERMISSIONS,
				GroupTrMemberInvites.permissionsSignedInput(otherGroup,
						b.getLong(8), b.getLong(9))));
		deliver(member, resign(b));
		assertUnavailable(() -> invitee.manager.acceptMemberInvite(
				otherGroup, invitee.contactIdOf(member)));
	}

	@Test
	public void anOfferWithoutTheInvitePermissionIsRefused()
			throws Exception {
		long ts = offer.getLong(9) + 1;
		byte[] sig = InviteTestNet.sign(creator,
				GroupTrMemberInvites.LABEL_PERMISSIONS,
				GroupTrMemberInvites.permissionsSignedInput(groupId, 0L, ts));
		BdfList b = with(8, 0L, false);
		b.set(9, ts);
		b.set(10, sig);
		deliver(member, resign(b));
		assertNotAdmitted();
	}

	@Test
	public void anOfferWithAPermissionNotSignedByTheCreatorIsRefused()
			throws Exception {
		long ts = offer.getLong(9) + 1;
		byte[] sig = InviteTestNet.sign(member,
				GroupTrMemberInvites.LABEL_PERMISSIONS,
				GroupTrMemberInvites.permissionsSignedInput(groupId, 1L, ts));
		BdfList b = with(9, ts, false);
		b.set(10, sig);
		deliver(member, resign(b));
		assertNotAdmitted();
	}

	@Test
	public void anExpiredOfferIsRefused() throws Exception {
		invitee.clockOverride = offer.getLong(11) + 8 * DAY;
		deliver(member, offer);
		assertNotAdmitted();
	}

	@Test
	public void anOfferWithAStalePermissionIsRefused() throws Exception {
		long stale = net.now - GroupTrMemberInvites.PERMISSIONS_MAX_AGE_MS
				- 1L;
		BdfList b = with(9, stale, false);
		b.set(10, InviteTestNet.sign(creator,
				GroupTrMemberInvites.LABEL_PERMISSIONS,
				GroupTrMemberInvites.permissionsSignedInput(groupId, 1L,
						stale)));
		deliver(member, resign(b));
		assertNotAdmitted();
	}

	@Test
	public void theCardOfOneInviterNeverAnswersAnotherInvitersOffer()
			throws Exception {
		deliver(member, offer);
		other.manager.inviteContactAsMember(groupId,
				other.contactIdOf(invitee));
		net.pump(p -> p.type() == 52);

		assertUnavailable(() -> invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(other)));
		assertNull("the other inviter's card joined through the first",
				invitee.manager.getGroup(groupId));
		assertUnavailable(() -> invitee.manager.declineMemberInvite(groupId,
				invitee.contactIdOf(other)));
		for (InviteTestNet.Packet p : invitee.sent()) {
			assertTrue("nothing answers an offer of another inviter",
					p.type() != 50 && p.type() != 51);
		}

		invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(member));
		GroupTrState s = invitee.manager.getGroup(groupId);
		assertNotNull("the first inviter's card still answers its offer", s);
		boolean viaOther = false;
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), other.pub)) viaOther = true;
		}
		assertFalse(viaOther);
	}

	@Test
	public void aCardWithoutAPendingOfferCannotBeDeclinedAsIfAnswered()
			throws Exception {
		assertUnavailable(() -> invitee.manager.declineMemberInvite(groupId,
				invitee.contactIdOf(member)));
	}

	@Test
	public void anOfferFromTheFutureIsRefused() throws Exception {
		invitee.clockOverride = offer.getLong(11) - 2 * DAY;
		deliver(member, offer);
		assertNotAdmitted();
	}

	@Test
	public void anOfferNamingAnotherCreatorKeyThanTheContactsIsRefused()
			throws Exception {
		BdfList b = with(6, wrongCreatorKey(), false);
		b.set(10, InviteTestNet.signature(
				GroupTrMemberInvites.LABEL_PERMISSIONS,
				GroupTrMemberInvites.permissionsSignedInput(groupId,
						b.getLong(8), b.getLong(9)), creator.pub,
				wrongCreatorKey()));
		BdfList signedForTheWrongKey = resign(b);
		deliver(member, signedForTheWrongKey);
		invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(member));
		assertTrue("without a contact record the inviter's word is taken",
				invitee.manager.isWaitingForAdmin(groupId));
		invitee.manager.removeFromDevice(groupId);

		net.connect(creator, invitee);
		deliver(member, signedForTheWrongKey);
		assertNotAdmitted();
	}

	private byte[] wrongCreatorKey() {
		byte[] wrong = creator.mlDsa.clone();
		wrong[0] ^= 1;
		return wrong;
	}

	@Test
	public void anOfferForAGroupAlreadyHeldIsRefused() throws Exception {
		net.connect(creator, invitee);
		join(invitee);
		deliver(member, offer);
		assertNotAdmitted();
	}

	@Test
	public void aLaterOfferReplacesAJoiningStateTheCreatorNeverConfirmed()
			throws Exception {
		deliver(member, offer);
		invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(member));
		net.pump(p -> p.type() == 52);
		assertTrue(invitee.manager.isWaitingForAdmin(groupId));

		other.manager.inviteContactAsMember(groupId,
				other.contactIdOf(invitee));
		net.pump(p -> p.type() == 52);
		invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(other));

		GroupTrState s = invitee.manager.getGroup(groupId);
		assertNotNull(s);
		boolean viaOther = false;
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), other.pub)) viaOther = true;
		}
		assertTrue(viaOther);
		net.release();
		assertTrue(creator.manager.isMember(groupId, invitee.pub));
		assertFalse(invitee.manager.isWaitingForAdmin(groupId));
	}

	private void assertNotAdmitted() throws Exception {
		assertUnavailable(() -> invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(member)));
	}

	private void deliver(InviteTestNet.Node from, BdfList body) {
		invitee.receive(net.packet(from, invitee, body));
	}

	private BdfList with(int index, Object value, boolean resign)
			throws Exception {
		BdfList b = new BdfList();
		b.addAll(offer);
		b.set(index, value);
		return resign ? resign(b) : b;
	}

	private BdfList resign(BdfList b) throws Exception {
		byte[] signed = GroupTrMemberInvites.offerSignedInput(b.getRaw(1),
				b.getRaw(7), invitee.pub, b.getRaw(5), b.getLong(11),
				b.getString(2), b.getRaw(3), b.getString(4), b.getRaw(6),
				b.getLong(8), b.getLong(9), b.getRaw(10));
		b.set(12, InviteTestNet.sign(member,
				GroupTrMemberInvites.LABEL_OFFER, signed));
		return b;
	}

	private void join(InviteTestNet.Node n) throws Exception {
		creator.manager.inviteContactToGroup(groupId, creator.contactIdOf(n),
				n.pub, n.name);
		net.pump(p -> p.type() == 49);
		n.manager.acceptInvite(groupId);
		net.pump(p -> p.type() == 49);
		assertTrue(creator.manager.isMember(groupId, n.pub));
	}
}
