package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.data.BdfList;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.grouptr.GroupKeysKit.binding;
import static org.zerionproject.app.grouptr.GroupKeysKit.freshGroupKey;
import static org.zerionproject.app.grouptr.GroupKeysKit.join;
import static org.zerionproject.app.grouptr.GroupKeysKit.key;
import static org.zerionproject.app.grouptr.GroupKeysKit.probeEntry;
import static org.zerionproject.app.grouptr.GroupKeysKit.secretOf;
import static org.zerionproject.app.grouptr.GroupKeysKit.remotePostsAccepted;
import static org.zerionproject.app.grouptr.GroupKeysKit.sentOfType;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupKeyExchangeTest {

	private static final long DAY = 24L * 60L * 60L * 1000L;

	private final InviteTestNet net = new InviteTestNet();
	private final InviteTestNet.Node carol = net.node("Carol", 1);
	private final InviteTestNet.Node alice = net.node("Alice", 2);
	private final InviteTestNet.Node dave = net.node("Dave", 4);
	private final InviteTestNet.Node erin = net.node("Erin", 5);

	private int bindingsTo(InviteTestNet.Node from, InviteTestNet.Node to) {
		int n = 0;
		for (InviteTestNet.Packet p : from.sent()) {
			if (p.to == to && p.type() == MessageTypes.GROUP_KEY_BINDING) n++;
		}
		return n;
	}

	private void probeBothWays(InviteTestNet.Node from, InviteTestNet.Node to,
			byte[] groupId, byte[] secret, byte[] claimedKey)
			throws Exception {
		byte[] nonce = new byte[32];
		nonce[0] = 7;
		byte[] tag = InviteTestNet.hash(GroupTrKeys.LABEL_PROBE,
				new byte[][] {groupId, nonce});
		to.receive(net.packet(from, to, BdfList.of(
				(long) MessageTypes.GROUP_KEY_PROBE, nonce, tag)));
		BdfList binding = binding(groupId, from, claimedKey, net.now, true,
				true);
		to.receive(net.packet(from, to, BdfList.of(
				(long) MessageTypes.GROUP_KEY_PROBE,
				BdfList.of(BdfList.of(nonce, tag, binding)))));
		to.receive(net.packet(from, to, BdfList.of(
				(long) MessageTypes.GROUP_KEY_PROBE, BdfList.of(
						probeEntry(groupId, nonce, binding),
						probeEntry(secret, nonce, binding)))));
		net.pump();
	}

	private void setClock(long t) {
		for (InviteTestNet.Node n : net.nodes) n.clockOverride = t;
	}

	@Test
	public void aContactWhoDeclinedGetsNoAnswer() throws Exception {
		net.connect(carol, dave);
		net.connect(carol, erin);
		net.connect(erin, dave);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, dave);
		carol.manager.inviteContactToGroup(g, carol.contactIdOf(erin),
				erin.pub, erin.name);
		net.pump();
		erin.manager.declineInvite(g);
		net.pump();
		int before = bindingsTo(dave, erin);
		probeBothWays(erin, dave, g, g, freshGroupKey()[0]);
		assertEquals("a contact who only knows the group id learned that"
				+ " Dave is a member", before, bindingsTo(dave, erin));
	}

	@Test
	public void aRemovedMemberCannotWatchAMemberContact() throws Exception {
		net.connect(carol, alice);
		net.connect(carol, dave);
		net.connect(alice, dave);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, dave);
		join(net, carol, g, alice);
		byte[] old = key(alice, g);
		byte[] heldSecret = secretOf(alice, g);
		carol.manager.removeMember(g, old);
		net.pump();
		int before = bindingsTo(dave, alice);
		for (int day = 1; day <= 3; day++) {
			setClock(net.now + day * 2 * DAY);
			probeBothWays(alice, dave, g, heldSecret, old);
			probeBothWays(alice, dave, g, heldSecret, freshGroupKey()[0]);
		}
		assertEquals(before, bindingsTo(dave, alice));
	}

	@Test
	public void aJoiningMemberIsAnsweredOnceTheGroupListsIt()
			throws Exception {
		net.connect(carol, dave);
		net.connect(carol, erin);
		net.connect(erin, dave);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, dave);
		carol.manager.inviteContactToGroup(g, carol.contactIdOf(erin),
				erin.pub, erin.name);
		net.pump();
		erin.manager.acceptInvite(g);
		net.pump(p -> p.to == dave && (p.type() == 33 || p.type() == 41));
		assertEquals(1, sentOfType(erin, MessageTypes.GROUP_KEY_PROBE).size());
		assertEquals("Dave answered before the group listed Erin", 0,
				bindingsTo(dave, erin));
		net.release();
		assertEquals(1, bindingsTo(dave, erin));
		assertEquals(dave.contactIdOf(erin), dave.manager
				.getMemberContacts(g).get(toHexString(key(erin, g))));
		assertEquals(erin.contactIdOf(dave), erin.manager
				.getMemberContacts(g).get(toHexString(key(dave, g))));
	}

	@Test
	public void aMemberWhoRejoinsTheSameDayIsReachedAgain()
			throws Exception {
		net.connect(carol, alice);
		net.connect(carol, dave);
		net.connect(alice, dave);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, alice);
		join(net, carol, g, dave);
		alice.manager.leaveGroup(g);
		net.pump();
		join(net, carol, g, alice);
		alice.manager.sendGroupPost(g, new byte[] {2}, 0L);
		dave.manager.sendGroupPost(g, new byte[] {3}, 0L);
		net.pump();
		assertEquals(dave.contactIdOf(alice), dave.manager
				.getMemberContacts(g).get(toHexString(key(alice, g))));
		assertEquals(1, remotePostsAccepted(dave, g));
		assertEquals(1, remotePostsAccepted(alice, g));
	}

	@Test
	public void aRejoinAfterAClockThatRanAheadIsReachedAgain()
			throws Exception {
		net.connect(carol, alice);
		net.connect(carol, dave);
		net.connect(alice, dave);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, dave);
		alice.clockOverride = net.now + DAY / 2;
		join(net, carol, g, alice);
		alice.manager.leaveGroup(g);
		net.pump();
		alice.clockOverride = null;
		join(net, carol, g, alice);
		alice.manager.sendGroupPost(g, new byte[] {4}, 0L);
		net.pump();
		assertEquals(dave.contactIdOf(alice), dave.manager
				.getMemberContacts(g).get(toHexString(key(alice, g))));
		assertEquals(1, remotePostsAccepted(dave, g));
	}

	@Test
	public void aKeyIsAnsweredOnceADayAlsoAfterARestart() throws Exception {
		net.connect(carol, alice);
		net.connect(carol, dave);
		net.connect(alice, dave);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, alice);
		join(net, carol, g, dave);
		byte[] k = key(alice, g);
		byte[] secret = secretOf(alice, g);
		int start = bindingsTo(dave, alice);
		probeBothWays(alice, dave, g, secret, k);
		assertEquals(start + 1, bindingsTo(dave, alice));
		probeBothWays(alice, dave, g, secret, k);
		assertEquals(start + 1, bindingsTo(dave, alice));
		dave.restart();
		probeBothWays(alice, dave, g, secret, k);
		assertEquals(start + 1, bindingsTo(dave, alice));
		setClock(net.now + 2 * DAY);
		probeBothWays(alice, dave, g, secret, k);
		assertEquals(start + 2, bindingsTo(dave, alice));
	}

	@Test
	public void aContactThatUpdatesGetsOnePaddedProbeForEveryGroup()
			throws Exception {
		net.connect(carol, dave);
		net.connect(carol, erin);
		byte[][] groups = new byte[3][];
		for (int i = 0; i < groups.length; i++) {
			groups[i] = carol.manager.createGroup("G" + i).getGroupId();
			join(net, carol, groups[i], dave);
			join(net, carol, groups[i], erin);
		}
		net.connect(dave, erin);
		dave.versionUpdated(erin, 13);
		net.pump();
		List<InviteTestNet.Packet> probes =
				sentOfType(dave, MessageTypes.GROUP_KEY_PROBE);
		assertEquals(1, probes.size());
		assertEquals(GroupTrKeyExchange.PROBE_SLOTS,
				probes.get(0).body.getList(1).size());
		for (byte[] g : groups) {
			assertEquals(erin.contactIdOf(dave), erin.manager
					.getMemberContacts(g).get(toHexString(key(dave, g))));
			assertEquals(dave.contactIdOf(erin), dave.manager
					.getMemberContacts(g).get(toHexString(key(erin, g))));
		}
		dave.versionUpdated(erin, 13);
		net.pump();
		assertEquals(1, sentOfType(dave, MessageTypes.GROUP_KEY_PROBE).size());
	}

	@Test
	public void joinProbesWaitAFewMinutesAndSurviveARestart()
			throws Exception {
		net.connect(carol, dave);
		net.connect(carol, erin);
		net.connect(erin, dave);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, dave);
		erin.holdScheduled = true;
		join(net, carol, g, erin);
		assertTrue(sentOfType(erin, MessageTypes.GROUP_KEY_PROBE).isEmpty());
		assertEquals(1, erin.scheduledDelays.size());
		long delay = erin.scheduledDelays.get(0);
		assertTrue(delay >= GroupTrKeyExchange.PROBE_DELAY_MIN_MS);
		assertTrue(delay <= GroupTrKeyExchange.PROBE_DELAY_MIN_MS
				+ GroupTrKeyExchange.PROBE_DELAY_SPREAD_MS);
		erin.scheduled.clear();
		erin.scheduledDelays.clear();
		erin.restart();
		assertTrue("a restart before the probe is due waits",
				sentOfType(erin, MessageTypes.GROUP_KEY_PROBE).isEmpty());
		assertEquals(1, erin.scheduledDelays.size());
		assertEquals(delay, (long) erin.scheduledDelays.get(0));
		erin.scheduled.clear();
		erin.holdScheduled = false;
		erin.clockOverride = net.now + delay;
		erin.restart();
		net.pump();
		assertEquals(1, sentOfType(erin, MessageTypes.GROUP_KEY_PROBE).size());
		assertEquals(dave.contactIdOf(erin), dave.manager
				.getMemberContacts(g).get(toHexString(key(erin, g))));
	}

	@Test
	public void aLeaveConfirmedBeforeItArrivesIsNotARemoval()
			throws Exception {
		net.connect(carol, alice);
		net.connect(carol, dave);
		net.connect(alice, dave);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, alice);
		join(net, carol, g, dave);
		carol.manager.setMembersMayInvite(g, true);
		net.pump();
		alice.manager.leaveGroup(g);
		net.pump(p -> p.to == dave && p.type() == 35);
		net.release();
		dave.manager.inviteContactAsMember(g, dave.contactIdOf(alice));
		net.pump();
		assertEquals(1, sentOfType(dave,
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER).size());
	}

	@Test
	public void aMemberWhoNeverKnewTheRemovedKeyCannotBringThemBack()
			throws Exception {
		InviteTestNet.Node rita = net.node("Rita", 6);
		InviteTestNet.Node mia = net.node("Mia", 7);
		net.connect(carol, rita);
		net.connect(carol, mia);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, rita);
		join(net, carol, g, mia);
		carol.manager.removeMember(g, key(rita, g));
		carol.manager.setMembersMayInvite(g, true);
		net.pump();
		net.connect(mia, rita);
		mia.manager.inviteContactAsMember(g, mia.contactIdOf(rita));
		net.pump();
		rita.manager.acceptMemberInvite(g, rita.contactIdOf(mia));
		net.pump();
		assertEquals(1, sentOfType(mia, MessageTypes.GROUPTR_JOIN_REQUEST)
				.size());
		assertFalse("the creator admitted a person it removed",
				carol.manager.isMember(g, key(rita, g)));
	}

	@Test
	public void aRemovedPersonCannotComeBackThroughAnotherMember()
			throws Exception {
		InviteTestNet.Node rita = net.node("Rita", 6);
		InviteTestNet.Node mia = net.node("Mia", 7);
		net.connect(carol, dave);
		net.connect(carol, mia);
		net.connect(dave, rita);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, dave);
		join(net, carol, g, mia);
		carol.manager.setMembersMayInvite(g, true);
		net.pump();
		dave.manager.inviteContactAsMember(g, dave.contactIdOf(rita));
		net.pump();
		rita.manager.acceptMemberInvite(g, rita.contactIdOf(dave));
		net.pump();
		byte[] old = key(rita, g);
		assertTrue(carol.manager.isMember(g, old));
		carol.manager.removeMember(g, old);
		net.pump();
		try {
			dave.manager.inviteContactAsMember(g, dave.contactIdOf(rita));
			fail("the inviting contact invited a removed person again");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason
					.MEMBER_INVITES_UNAVAILABLE, e.getReason());
		}
		net.connect(mia, rita);
		mia.manager.inviteContactAsMember(g, mia.contactIdOf(rita));
		net.pump();
		rita.manager.acceptMemberInvite(g, rita.contactIdOf(mia));
		net.pump();
		byte[] again = key(rita, g);
		assertFalse(java.util.Arrays.equals(old, again));
		assertFalse(carol.manager.isMember(g, again));
	}

	@Test
	public void theCreatorStillAdmitsSomeoneWhoLeftOnTheirOwn()
			throws Exception {
		InviteTestNet.Node mia = net.node("Mia", 7);
		net.connect(carol, alice);
		net.connect(carol, mia);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, alice);
		join(net, carol, g, mia);
		alice.manager.leaveGroup(g);
		carol.manager.setMembersMayInvite(g, true);
		net.pump();
		net.connect(mia, alice);
		mia.manager.inviteContactAsMember(g, mia.contactIdOf(alice));
		net.pump();
		alice.manager.acceptMemberInvite(g, alice.contactIdOf(mia));
		net.pump();
		assertTrue(carol.manager.isMember(g, key(alice, g)));
		assertArrayEquals(key(alice, g), carol.manager.getGroup(g)
				.getMembers().get(carol.manager.getGroup(g).getMembers()
						.size() - 1).getPubKey());
	}
}
