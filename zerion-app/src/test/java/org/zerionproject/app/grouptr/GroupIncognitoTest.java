package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.data.BdfList;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GroupIncognitoTest {

	private static final String CREATOR_ALIAS = "Amber Falcon 27";
	private static final String MEMBER_ALIAS = "Quiet Otter 42";
	private static final String INVITEE_ALIAS = "Silver Fox 33";

	private final InviteTestNet net = new InviteTestNet();
	private final InviteTestNet.Node creator = net.node("Carol", 1);
	private final InviteTestNet.Node member = net.node("Mia", 2);
	private final InviteTestNet.Node invitee = net.node("Xavi", 3);

	@Before
	public void setUp() {
		net.connect(creator, member);
		net.connect(member, invitee);
	}

	@Test
	public void aGroupCreatedIncognitoNeverCarriesTheCreatorsName()
			throws Exception {
		GroupTrState s = creator.manager.createGroup("Club", CREATOR_ALIAS);
		byte[] groupId = s.getGroupId();
		assertTrue(s.usesGroupKeys());
		assertEquals(CREATOR_ALIAS, s.getCreatorName());
		assertEquals(CREATOR_ALIAS, creator.manager.getIncognitoName(groupId));
		assertEquals(CREATOR_ALIAS, member(creator, groupId,
				key(creator, groupId)).getName());

		creator.manager.inviteContactToGroup(groupId,
				creator.contactIdOf(member), member.pub, member.name);
		net.pump();
		member.manager.acceptInvite(groupId);
		net.pump();
		assertTrue("the group id was derived from the random name",
				creator.manager.isMember(groupId, key(member, groupId)));
		assertEquals(CREATOR_ALIAS, member.manager.getGroup(groupId)
				.getCreatorName());
		creator.manager.setMembersMayInvite(groupId, true);
		net.pump();
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		creator.manager.sendGroupPost(groupId, new byte[] {1}, 0L);
		net.pump();

		BdfList post = sentOfType(creator, MessageTypes.GROUP_POST).get(0)
				.body;
		assertEquals(CREATOR_ALIAS, post.getString(4));
		BdfList offer = sentOfType(member,
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER).get(0).body;
		assertEquals(CREATOR_ALIAS, offer.getString(4));
		assertNameNeverSent(creator.name);
	}

	@Test
	public void aContactJoiningIncognitoIsAddedUnderItsRandomName()
			throws Exception {
		byte[] groupId = creator.manager.createGroup("Club").getGroupId();
		assertTrue(creator.manager.isMember(groupId, key(creator, groupId)));
		creator.manager.inviteContactToGroup(groupId,
				creator.contactIdOf(member), member.pub, member.name);
		net.pump();
		assertTrue(member.manager.canAcceptIncognito(groupId, false));

		member.manager.acceptInvite(groupId, MEMBER_ALIAS);
		net.pump();

		assertTrue(sentOfType(member, MessageTypes.GROUPTR_INVITE_ACCEPT)
				.isEmpty());
		assertEquals(1, sentOfType(member,
				MessageTypes.GROUPTR_INVITE_ACCEPT_INCOGNITO).size());
		byte[] memberKey = key(member, groupId);
		assertTrue(creator.manager.isMember(groupId, memberKey));
		assertEquals(MEMBER_ALIAS, member(creator, groupId, memberKey)
				.getName());
		BdfList added = sentOfType(creator, MessageTypes.GROUP_MEMBER_ADDED)
				.get(0).body;
		assertEquals(MEMBER_ALIAS, added.getString(3));
		assertEquals(MEMBER_ALIAS, member.manager.getIncognitoName(groupId));
		assertEquals(MEMBER_ALIAS, member(member, groupId, memberKey)
				.getName());

		member.manager.sendGroupPost(groupId, new byte[] {2}, 0L);
		net.pump();
		assertEquals(MEMBER_ALIAS, sentOfType(member, MessageTypes.GROUP_POST)
				.get(0).body.getString(4));
		assertNameNeverSent(member.name);
	}

	@Test
	public void aContactInvitedByAMemberJoinsIncognito() throws Exception {
		byte[] groupId = creator.manager.createGroup("Club").getGroupId();
		creator.manager.inviteContactToGroup(groupId,
				creator.contactIdOf(member), member.pub, member.name);
		net.pump();
		member.manager.acceptInvite(groupId);
		net.pump();
		creator.manager.setMembersMayInvite(groupId, true);
		net.pump();
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		net.pump();
		assertTrue(invitee.manager.canAcceptIncognito(groupId, true));

		invitee.manager.acceptMemberInvite(groupId,
				invitee.contactIdOf(member), INVITEE_ALIAS);
		net.pump();

		assertTrue(sentOfType(invitee,
				MessageTypes.GROUPTR_MEMBER_INVITE_ACCEPT).isEmpty());
		assertEquals(1, sentOfType(invitee,
				MessageTypes.GROUPTR_MEMBER_INVITE_ACCEPT_INCOGNITO).size());
		BdfList request = sentOfType(member, MessageTypes.GROUPTR_JOIN_REQUEST)
				.get(0).body;
		assertEquals(INVITEE_ALIAS, request.getString(3));
		byte[] inviteeKey = key(invitee, groupId);
		assertTrue(creator.manager.isMember(groupId, inviteeKey));
		assertEquals(INVITEE_ALIAS, member(creator, groupId, inviteeKey)
				.getName());
		assertTrue(invitee.manager.isMember(groupId, inviteeKey));
		assertEquals(INVITEE_ALIAS,
				invitee.manager.getIncognitoName(groupId));

		invitee.manager.sendGroupPost(groupId, new byte[] {3}, 0L);
		net.pump();
		assertEquals(INVITEE_ALIAS, sentOfType(invitee,
				MessageTypes.GROUP_POST).get(0).body.getString(4));
		assertNameNeverSent(invitee.name);
	}

	@Test
	public void incognitoIsNotOfferedForAnInvitationToAnOlderGroup()
			throws Exception {
		byte[] groupId = creator.manager.createLegacyGroup("Club", null)
				.getGroupId();
		creator.manager.inviteContactToGroup(groupId,
				creator.contactIdOf(member), member.pub, member.name);
		net.pump();

		assertFalse(member.manager.canAcceptIncognito(groupId, false));
		try {
			member.manager.acceptInvite(groupId, MEMBER_ALIAS);
			fail("an older creator would have shared the account name");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.INCOGNITO_NOT_SUPPORTED,
					e.getReason());
		}
		assertTrue(sentOfType(member, MessageTypes.GROUPTR_INVITE_ACCEPT)
				.isEmpty());
		assertTrue(sentOfType(member,
				MessageTypes.GROUPTR_INVITE_ACCEPT_INCOGNITO).isEmpty());
		assertNull(member.manager.getGroup(groupId));
	}

	@Test
	public void incognitoIsNotOfferedForAMemberInviteToAnOlderGroup()
			throws Exception {
		byte[] groupId = creator.manager.createLegacyGroup("Club", null)
				.getGroupId();
		creator.manager.inviteContactToGroup(groupId,
				creator.contactIdOf(member), member.pub, member.name);
		net.pump();
		member.manager.acceptInvite(groupId);
		net.pump();
		creator.manager.setMembersMayInvite(groupId, true);
		net.pump();
		member.manager.inviteContactAsMember(groupId,
				member.contactIdOf(invitee));
		net.pump();

		assertFalse(invitee.manager.canAcceptIncognito(groupId, true));
		try {
			invitee.manager.acceptMemberInvite(groupId,
					invitee.contactIdOf(member), INVITEE_ALIAS);
			fail("an older member would have shared the account name");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.INCOGNITO_NOT_SUPPORTED,
					e.getReason());
		}
		assertTrue(invitee.sent().isEmpty());
	}

	@Test
	public void theIncognitoNameCannotBeReplaced() throws Exception {
		byte[] groupId = creator.manager.createGroup("Club", CREATOR_ALIAS)
				.getGroupId();
		try {
			creator.manager.setStealthName(groupId, "Carol here");
			fail("the incognito name was replaced");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.INCOGNITO_NAME_FIXED,
					e.getReason());
		}
		try {
			creator.manager.setStealthName(groupId, null);
			fail("the incognito name was cleared");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.INCOGNITO_NAME_FIXED,
					e.getReason());
		}
		assertEquals(CREATOR_ALIAS, creator.manager.getStealthName(groupId));
	}

	@Test
	public void aGroupWithoutIncognitoKeepsTheChosenName() throws Exception {
		byte[] groupId = creator.manager.createGroup("Club").getGroupId();
		assertNull(creator.manager.getIncognitoName(groupId));
		creator.manager.setStealthName(groupId, "Captain");
		assertEquals("Captain", creator.manager.getStealthName(groupId));
	}

	@Test
	public void onlyAGeneratedNameIsTakenAsAnIncognitoName()
			throws Exception {
		try {
			creator.manager.createGroup("Club", creator.name);
			fail("an account name was taken as an incognito name");
		} catch (IllegalArgumentException expected) {
		}
		byte[] groupId = creator.manager.createGroup("Club").getGroupId();
		creator.manager.inviteContactToGroup(groupId,
				creator.contactIdOf(member), member.pub, member.name);
		net.pump();
		try {
			member.manager.acceptInvite(groupId, member.name);
			fail("an account name was taken as an incognito name");
		} catch (IllegalArgumentException expected) {
		}
		assertTrue(member.sent().isEmpty());
	}

	private static byte[] key(InviteTestNet.Node n, byte[] groupId)
			throws Exception {
		return n.manager.getLocalMemberKey(groupId);
	}

	private void assertNameNeverSent(String name) {
		for (InviteTestNet.Packet p : net.delivered) {
			assertFalse("type " + p.type() + " from " + p.from.name
					+ " carries " + name, carries(p.body, name));
		}
	}

	private static boolean carries(Object o, String name) {
		if (o instanceof String) return o.equals(name);
		if (o instanceof List) {
			for (Object item : (List<?>) o) {
				if (carries(item, name)) return true;
			}
		}
		return false;
	}

	private static GroupTrMember member(InviteTestNet.Node n, byte[] groupId,
			byte[] pub) throws Exception {
		GroupTrState s = n.manager.getGroup(groupId);
		assertNotNull(s);
		for (GroupTrMember m : s.getMembers()) {
			if (Arrays.equals(m.getPubKey(), pub)) return m;
		}
		throw new AssertionError("not a member");
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
