package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.messaging.event.GroupTrPostAcceptedEvent;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GroupRecordOrderNetTest {

	private final InviteTestNet net = new InviteTestNet();
	private final InviteTestNet.Node creator = net.node("Carol", 1);
	private final InviteTestNet.Node early = net.node("Mia", 2);
	private final InviteTestNet.Node first = net.node("Finn", 3);
	private final InviteTestNet.Node second = net.node("Sara", 4);
	private byte[] groupId;

	@Before
	public void setUp() throws Exception {
		net.connect(creator, early);
		net.connect(creator, first);
		net.connect(creator, second);
		net.connect(early, first);
		groupId = creator.manager.createLegacyGroup("Club", null).getGroupId();
		join(early, p -> false);
	}

	@Test
	public void aMemberAddedJustBeforeAnotherLearnsTheEarlierMembers()
			throws Exception {
		Predicate<InviteTestNet.Packet> snapshotsToFirst =
				p -> p.type() == 41 && p.to == first;
		join(first, snapshotsToFirst);
		join(second, snapshotsToFirst);
		net.release();

		assertTrue("the first new member must learn the member that was"
				+ " there before it", first.manager.isMember(groupId,
				early.pub));
		assertTrue(first.manager.isMember(groupId, second.pub));
		assertEquals(creator.manager.getEpoch(groupId),
				first.manager.getEpoch(groupId));

		early.manager.sendGroupPost(groupId, bytes("hello"), 0L);
		net.pump();
		assertTrue("the first new member takes the earlier member's post",
				acceptedPostAt(first));
	}

	@Test
	public void aRemovalWhoseCommitArrivedFirstStillAppliesEverywhere()
			throws Exception {
		join(first, p -> false);
		join(second, p -> false);
		Predicate<InviteTestNet.Packet> removalsToEarly =
				p -> p.type() == 34 && p.to == early;

		creator.manager.removeMember(groupId, second.pub);
		net.pump(removalsToEarly);
		assertTrue(early.manager.isMember(groupId, second.pub));
		InviteTestNet.Node later = net.node("Lena", 5);
		net.connect(creator, later);
		join(later, removalsToEarly);
		net.release();

		assertFalse("the removal must apply on a device that took the"
				+ " commit and a later addition first",
				early.manager.isMember(groupId, second.pub));
		assertTrue(early.manager.isMember(groupId, later.pub));
		assertFalse(first.manager.isMember(groupId, second.pub));
		assertEquals(creator.manager.getEpoch(groupId),
				early.manager.getEpoch(groupId));
	}

	private void join(InviteTestNet.Node n,
			Predicate<InviteTestNet.Packet> hold) throws Exception {
		creator.manager.inviteContactToGroup(groupId, creator.contactIdOf(n),
				n.pub, n.name);
		net.pump(hold);
		n.manager.acceptInvite(groupId);
		net.pump(hold);
		assertTrue(creator.manager.isMember(groupId, n.pub));
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

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.UTF_8);
	}
}
