package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.grouptr.GroupMemberInviteFlowTest.assertUnavailable;
import static org.zerionproject.app.grouptr.GroupTrMemberInvites.LABEL_ACCEPT;
import static org.zerionproject.app.grouptr.GroupTrMemberInvites.LABEL_JOIN_REQUEST;
import static org.zerionproject.app.grouptr.GroupTrMemberInvites.joinRequestSignedInput;
import static org.zerionproject.app.grouptr.GroupTrMemberInvites.responseSignedInput;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupJoinRequestTest {

	private static final long DAY = 24L * 60L * 60L * 1000L;

	private final InviteTestNet net = new InviteTestNet();
	private final InviteTestNet.Node creator = net.node("Carol", 1);
	private final InviteTestNet.Node member = net.node("Mia", 2);
	private final InviteTestNet.Node invitee = net.node("Xavi", 3);
	private final InviteTestNet.Node outsider = net.node("Olga", 4);
	private byte[] groupId;

	@Before
	public void setUp() throws Exception {
		net.connect(creator, member);
		net.connect(creator, outsider);
		groupId = creator.manager.createGroup("Club").getGroupId();
		creator.manager.inviteContactToGroup(groupId,
				creator.contactIdOf(member), member.pub, member.name);
		net.pump();
		member.manager.acceptInvite(groupId);
		net.pump();
		assertTrue(creator.manager.isMember(groupId, member.pub));
	}

	@Test
	public void aValidRequestIsAddedAutomaticallyWithTheInviteesKey()
			throws Exception {
		enable();
		int snapshots = count(creator, 41, member);
		send(member, request(member, member, member, net.now, net.now));

		assertTrue(creator.manager.isMember(groupId, invitee.pub));
		GroupTrMember added = entry(invitee.pub);
		assertArrayEquals(invitee.mlDsa, added.getMlDsaPubKey());
		assertEquals("Xavi", added.getName());
		assertEquals(snapshots + 1, count(creator, 41, member));
	}

	@Test
	public void nothingHappensWhileTheSettingIsOff() throws Exception {
		send(member, request(member, member, member, net.now, net.now));
		assertFalse(creator.manager.isMember(groupId, invitee.pub));

		enable();
		creator.manager.setMembersMayInvite(groupId, false);
		net.pump();
		send(member, request(member, member, member, net.now, net.now));
		assertFalse(creator.manager.isMember(groupId, invitee.pub));
	}

	@Test
	public void aRequestFromAContactThatIsNotAMemberIsRefused()
			throws Exception {
		enable();
		send(outsider, request(outsider, outsider, outsider, net.now,
				net.now));
		assertFalse(creator.manager.isMember(groupId, invitee.pub));
	}

	@Test
	public void aRequestSignedByAnotherThanTheDeliveringMemberIsRefused()
			throws Exception {
		enable();
		send(member, request(member, outsider, member, net.now, net.now));
		assertFalse(creator.manager.isMember(groupId, invitee.pub));
	}

	@Test
	public void anAcceptanceNamingAnotherInviterIsRefused() throws Exception {
		enable();
		send(member, request(member, member, outsider, net.now, net.now));
		assertFalse(creator.manager.isMember(groupId, invitee.pub));
	}

	@Test
	public void aForgedAcceptanceIsRefused() throws Exception {
		enable();
		BdfList r = request(member, member, member, net.now, net.now);
		byte[] acceptSig = r.getRaw(6).clone();
		acceptSig[3] ^= 1;
		r.set(6, acceptSig);
		r.set(8, InviteTestNet.sign(member, LABEL_JOIN_REQUEST,
				joinRequestSignedInput(groupId, member.pub, invitee.pub,
						r.getLong(5), r.getLong(7), r.getString(3),
						r.getRaw(4), acceptSig)));
		send(member, r);
		assertFalse(creator.manager.isMember(groupId, invitee.pub));
	}

	@Test
	public void staleOrFutureRequestsAreRefused() throws Exception {
		enable();
		send(member, request(member, member, member, net.now - 15 * DAY,
				net.now));
		send(member, request(member, member, member, net.now,
				net.now + 2 * DAY));
		assertFalse(creator.manager.isMember(groupId, invitee.pub));

		send(member, request(member, member, member, net.now - 13 * DAY,
				net.now - DAY));
		assertTrue("an acceptance that waited for the creator is taken",
				creator.manager.isMember(groupId, invitee.pub));
	}

	@Test
	public void aKeyOtherThanTheCreatorsContactRecordIsRefused()
			throws Exception {
		enable();
		net.connect(creator, invitee);
		byte[] wrong = invitee.mlDsa.clone();
		wrong[7] ^= 1;
		send(member, request(member, member, member, net.now, net.now,
				wrong));
		assertFalse(creator.manager.isMember(groupId, invitee.pub));

		send(member, request(member, member, member, net.now, net.now));
		assertTrue(creator.manager.isMember(groupId, invitee.pub));
	}

	@Test
	public void theCreatorCannotUseMemberInvites() throws Exception {
		enable();
		net.connect(creator, invitee);
		assertUnavailable(() -> creator.manager.inviteContactAsMember(
				groupId, creator.contactIdOf(invitee)));
	}

	@Test
	public void aRequestAddsTheInviteeOnceOnly() throws Exception {
		enable();
		BdfList r = request(member, member, member, net.now, net.now);
		send(member, r);
		long epoch = creator.manager.getEpoch(groupId);
		send(member, r);
		assertEquals(epoch, creator.manager.getEpoch(groupId));
		int listed = 0;
		GroupTrState s = creator.manager.getGroup(groupId);
		assertNotNull(s);
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), invitee.pub)) listed++;
		}
		assertEquals(1, listed);
	}

	@Test
	public void aMemberCannotBringBackSomeoneTheCreatorRemoved()
			throws Exception {
		enable();
		send(member, request(member, member, member, net.now, net.now));
		assertTrue(creator.manager.isMember(groupId, invitee.pub));
		creator.manager.removeMember(groupId, invitee.pub);
		net.pump();

		net.now += 1000L;
		send(member, request(member, member, member, net.now, net.now));
		assertFalse("a member undid the creator's removal",
				creator.manager.isMember(groupId, invitee.pub));
		assertTrue(removedByCreator().contains(toHexString(invitee.pub)));

		net.connect(creator, invitee);
		creator.manager.inviteContactToGroup(groupId,
				creator.contactIdOf(invitee), invitee.pub, invitee.name);
		net.pump();
		invitee.manager.acceptInvite(groupId);
		net.pump();
		assertTrue("the creator can still add the person itself",
				creator.manager.isMember(groupId, invitee.pub));
		assertFalse(removedByCreator().contains(toHexString(invitee.pub)));
	}

	@Test
	public void theCreatorRemembersALimitedNumberOfRemovals()
			throws Exception {
		int max = GroupTrMemberInvites.MAX_REMOVED_REMEMBERED;
		for (int i = 0; i <= max; i++) {
			creator.manager.addMember(groupId, filler(i), "F" + i);
			creator.manager.removeMember(groupId, filler(i));
		}

		String removed = removedByCreator();
		assertFalse(removed.contains(toHexString(filler(0))));
		assertTrue(removed.contains(toHexString(filler(1))));
		assertTrue(removed.contains(toHexString(filler(max))));
		assertEquals(max, removed.split(",").length);
	}

	@Test
	public void nobodyIsAddedBeyondTheGroupSizeLimit() throws Exception {
		enable();
		int max = GroupTrConstants.MAX_GROUP_MEMBERS;
		for (int i = 0; i < max - 2; i++) {
			creator.manager.addMember(groupId, filler(i), "F" + i);
		}
		net.pump();
		assertEquals(max, members());
		BdfList r = request(member, member, member, net.now, net.now);

		send(member, r);

		assertFalse(creator.manager.isMember(groupId, invitee.pub));
		assertGroupFull(() -> creator.manager.addMember(groupId,
				filler(max), "Extra"));
		assertGroupFull(() -> creator.manager.inviteContactToGroup(groupId,
				creator.contactIdOf(outsider), outsider.pub, outsider.name));
		assertEquals(max, members());

		creator.manager.removeMember(groupId, filler(0));
		net.pump();
		send(member, r);
		assertTrue("a request refused for size was not used up",
				creator.manager.isMember(groupId, invitee.pub));
		assertEquals(max, members());
	}

	private String removedByCreator() {
		String v = creator.namespace(GroupTrMemberInvites.nsOf(groupId))
				.get(GroupTrMemberInvites.K_REMOVED);
		return v == null ? "" : v;
	}

	private int members() throws Exception {
		GroupTrState s = creator.manager.getGroup(groupId);
		assertNotNull(s);
		return s.getMembers().size();
	}

	private static byte[] filler(int i) {
		byte[] k = new byte[32];
		Arrays.fill(k, (byte) 0x7F);
		k[1] = (byte) i;
		k[2] = (byte) (i >> 8);
		return k;
	}

	private static void assertGroupFull(GroupMemberInviteFlowTest.Call c)
			throws Exception {
		try {
			c.run();
			fail("a full group took another member");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.GROUP_FULL,
					e.getReason());
		}
	}

	private void enable() throws Exception {
		creator.manager.setMembersMayInvite(groupId, true);
		net.pump();
	}

	private void send(InviteTestNet.Node from, BdfList body) {
		creator.receive(net.packet(from, creator, body));
		net.pump();
	}

	private BdfList request(InviteTestNet.Node namedInviter,
			InviteTestNet.Node requestSigner,
			InviteTestNet.Node acceptedInviter, long acceptTs,
			long requestTs) {
		return request(namedInviter, requestSigner, acceptedInviter,
				acceptTs, requestTs, invitee.mlDsa);
	}

	private BdfList request(InviteTestNet.Node namedInviter,
			InviteTestNet.Node requestSigner,
			InviteTestNet.Node acceptedInviter, long acceptTs,
			long requestTs, byte[] inviteeMlDsa) {
		byte[] acceptSig = InviteTestNet.signature(LABEL_ACCEPT,
				responseSignedInput(groupId, invitee.pub,
						acceptedInviter.pub, creator.pub, acceptTs,
						inviteeMlDsa), invitee.pub, inviteeMlDsa);
		byte[] requestSig = InviteTestNet.sign(requestSigner,
				LABEL_JOIN_REQUEST, joinRequestSignedInput(groupId,
						namedInviter.pub, invitee.pub, acceptTs, requestTs,
						invitee.name, inviteeMlDsa, acceptSig));
		BdfList b = new BdfList();
		b.addAll(Arrays.asList(52L, groupId, invitee.pub, invitee.name,
				inviteeMlDsa, acceptTs, acceptSig, requestTs, requestSig));
		return b;
	}

	private GroupTrMember entry(byte[] pub) throws Exception {
		GroupTrState s = creator.manager.getGroup(groupId);
		assertNotNull(s);
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pub)) return m;
		}
		throw new AssertionError("not listed");
	}

	private static int count(InviteTestNet.Node from, int type,
			InviteTestNet.Node to) {
		int n = 0;
		for (InviteTestNet.Packet p : from.sent()) {
			if (p.type() == type && p.to == to) n++;
		}
		return n;
	}
}
