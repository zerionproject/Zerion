package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.settings.Settings;
import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.grouptr.GroupKeysKit.binding;
import static org.zerionproject.app.grouptr.GroupKeysKit.carries;
import static org.zerionproject.app.grouptr.GroupKeysKit.containsKey;
import static org.zerionproject.app.grouptr.GroupKeysKit.deliveredTo;
import static org.zerionproject.app.grouptr.GroupKeysKit.freshGroupKey;
import static org.zerionproject.app.grouptr.GroupKeysKit.join;
import static org.zerionproject.app.grouptr.GroupKeysKit.key;
import static org.zerionproject.app.grouptr.GroupKeysKit.mlOf;
import static org.zerionproject.app.grouptr.GroupKeysKit.post;
import static org.zerionproject.app.grouptr.GroupKeysKit.raw;
import static org.zerionproject.app.grouptr.GroupKeysKit.remotePostsAccepted;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupMemberKeysTest {

	private final InviteTestNet net = new InviteTestNet();
	private final InviteTestNet.Node carol = net.node("Carol", 1);
	private final InviteTestNet.Node alice = net.node("Alice", 2);
	private final InviteTestNet.Node bob = net.node("Bob", 3);
	private final InviteTestNet.Node dave = net.node("Dave", 4);
	private byte[] first;
	private byte[] second;

	@Before
	public void setUp() throws Exception {
		net.connect(carol, alice);
		net.connect(carol, bob);
		net.connect(carol, dave);
		net.connect(alice, dave);
		first = carol.manager.createGroup("First").getGroupId();
		second = carol.manager.createGroup("Second").getGroupId();
		for (byte[] g : new byte[][] {first, second}) {
			join(net, carol, g, alice);
			join(net, carol, g, bob);
			join(net, carol, g, dave);
		}
	}

	@Test
	public void theAppAnnouncesTheVersionThatCarriesMemberKeys() {
		assertEquals(13, MessagingManager.GROUP_MEMBER_KEYS_MIN_VERSION);
		assertTrue(MessagingManager.GROUP_MEMBER_KEYS_MIN_VERSION
				> MessagingManager.SALTED_TEXT_MIN_VERSION);
		assertTrue(MessagingManager.MINOR_VERSION
				>= MessagingManager.GROUP_MEMBER_KEYS_MIN_VERSION);
	}

	@Test
	public void everyGroupGivesEachMemberItsOwnKey() throws Exception {
		GroupTrState s = carol.manager.getGroup(first);
		assertNotNull(s);
		assertTrue(s.usesGroupKeys());
		for (InviteTestNet.Node n : new InviteTestNet.Node[] {carol, alice,
				bob, dave}) {
			byte[] inFirst = key(n, first);
			byte[] inSecond = key(n, second);
			assertEquals(32, inFirst.length);
			assertFalse(Arrays.equals(inFirst, inSecond));
			assertFalse(Arrays.equals(inFirst, n.pub));
			assertFalse(Arrays.equals(inSecond, n.pub));
			assertFalse(carol.manager.isMember(first, n.pub));
		}
		assertArrayEquals(key(carol, first), s.getCreatorPubKey());
	}

	@Test
	public void aMemberWhoIsNotAContactSeesNothingThatLinksTheGroups()
			throws Exception {
		alice.manager.sendGroupPost(first, new byte[] {1}, 0L);
		alice.manager.sendGroupPost(second, new byte[] {2}, 0L);
		carol.manager.sendGroupPost(first, new byte[] {3}, 0L);
		carol.manager.sendGroupPost(second, new byte[] {4}, 0L);
		carol.manager.sendMemberListSnapshot(first);
		carol.manager.sendMemberListSnapshot(second);
		net.pump();

		Set<String> inFirst = new HashSet<>();
		Set<String> inSecond = new HashSet<>();
		for (InviteTestNet.Packet p : deliveredTo(net, bob)) {
			assertFalse("type " + p.type() + " shows Alice's account key",
					carries(p.body, alice.pub));
			assertFalse("type " + p.type() + " shows Alice's account key",
					carries(p.body, alice.mlDsa));
			assertFalse(carries(p.body, carol.pub));
			if (carries(p.body, first)) inFirst.addAll(raw(p.body));
			if (carries(p.body, second)) inSecond.addAll(raw(p.body));
		}
		assertTrue("Bob does see Alice in the first group",
				inFirst.contains(toHexString(key(alice, first))));
		assertTrue(inSecond.contains(toHexString(key(alice, second))));
		Set<String> shared = new HashSet<>(inFirst);
		shared.retainAll(inSecond);
		assertTrue("shared values " + shared, shared.isEmpty());
		assertFalse(containsKey(bob.manager.getMemberContacts(first),
				key(alice, first)));
		assertEquals(0, remotePostsFrom(bob, first, alice));
	}

	@Test
	public void anOlderGroupStillShowsTheSameAccountKeyInEveryGroup()
			throws Exception {
		byte[] one = carol.manager.createLegacyGroup("One", null)
				.getGroupId();
		byte[] two = carol.manager.createLegacyGroup("Two", null)
				.getGroupId();
		for (byte[] g : new byte[][] {one, two}) {
			for (InviteTestNet.Node n : new InviteTestNet.Node[] {alice,
					bob}) {
				carol.manager.inviteContactToGroup(g, carol.contactIdOf(n),
						n.pub, n.name);
				net.pump();
				n.manager.acceptInvite(g);
				net.pump();
			}
			carol.manager.sendMemberListSnapshot(g);
			net.pump();
		}
		assertArrayEquals(alice.pub, key(alice, one));
		assertArrayEquals(alice.pub, key(alice, two));
		boolean seen = false;
		for (InviteTestNet.Packet p : deliveredTo(net, bob)) {
			if (carries(p.body, alice.pub)) seen = true;
		}
		assertTrue("an older group keeps listing the account key", seen);
	}

	@Test
	public void aContactRecognisesTheMemberInEveryGroup() throws Exception {
		ContactId aliceAtDave = dave.contactIdOf(alice);
		Map<String, ContactId> firstContacts =
				dave.manager.getMemberContacts(first);
		Map<String, ContactId> secondContacts =
				dave.manager.getMemberContacts(second);
		assertEquals(aliceAtDave,
				firstContacts.get(toHexString(key(alice, first))));
		assertEquals(aliceAtDave,
				secondContacts.get(toHexString(key(alice, second))));
		assertFalse(containsKey(firstContacts, key(alice, second)));

		alice.manager.sendGroupPost(first, new byte[] {7}, 0L);
		net.pump();
		assertEquals(1, remotePostsAccepted(dave, first));
		assertEquals(0, remotePostsFrom(bob, first, alice));
	}

	@Test
	public void aKeyFromOneGroupCannotSpeakInAnother() throws Exception {
		byte[] keyInFirst = key(alice, first);
		long epoch = dave.manager.getEpoch(second);
		BdfList crossed = post(second, epoch, keyInFirst, keyInFirst,
				mlOf(keyInFirst), new byte[] {9}, net.now);
		dave.receive(net.packet(alice, dave, crossed));
		BdfList identity = post(second, epoch, alice.pub, alice.pub,
				alice.mlDsa, new byte[] {10}, net.now);
		dave.receive(net.packet(alice, dave, identity));
		assertEquals(0, remotePostsAccepted(dave, second));

		byte[] keyInSecond = key(alice, second);
		BdfList genuine = post(second, epoch, keyInSecond, keyInSecond,
				mlOf(keyInSecond), new byte[] {11}, net.now);
		dave.receive(net.packet(alice, dave, genuine));
		assertEquals(1, remotePostsAccepted(dave, second));

		long before = alice.manager.getEpoch(second);
		BdfList snapshot = snapshot(second, before + 1, key(carol, first),
				key(alice, second));
		alice.receive(net.packet(carol, alice, snapshot));
		assertEquals(before, alice.manager.getEpoch(second));
		assertTrue(alice.manager.isMember(second, key(dave, second)));
	}

	@Test
	public void aPostIsOnlyTakenFromTheContactWhoseKeySignedIt()
			throws Exception {
		carol.manager.sendGroupPost(second, new byte[] {12}, 0L);
		net.pump(p -> p.to == dave && p.type() == 32);
		InviteTestNet.Packet held = net.heldPackets().get(0);
		dave.receive(net.packet(alice, dave, held.body));
		assertEquals("a member relayed the creator's post", 0,
				remotePostsAccepted(dave, second));
		net.release();
		assertEquals(1, remotePostsAccepted(dave, second));
	}

	@Test
	public void aLeaveIsOnlyTakenFromTheMemberWhoLeaves() throws Exception {
		byte[] daveKey = key(dave, first);
		dave.manager.leaveGroup(first);
		net.pump(p -> p.to == carol && p.type() == 35);
		InviteTestNet.Packet held = net.heldPackets().get(0);
		carol.receive(net.packet(alice, carol, held.body));
		net.pump(p -> p.to == carol && p.type() == 35);
		assertTrue("a member relayed another member's leave",
				carol.manager.isMember(first, daveKey));
		net.release();
		assertFalse(carol.manager.isMember(first, daveKey));
	}

	@Test
	public void aBindingNeedsBothSignaturesFromTheSendingContact()
			throws Exception {
		byte[] k = key(bob, first);
		long ts = net.now + 1;
		byte[] aliceKey = key(alice, first);
		deliverBinding(alice, dave, first, binding(first, alice, k, ts,
				true, false));
		deliverBinding(alice, dave, first, binding(first, alice, k, ts,
				false, true));
		deliverBinding(alice, dave, first, binding(first, bob, k, ts,
				true, true));
		deliverBinding(alice, dave, first, binding(second, alice, k, ts,
				true, true));
		deliverBinding(carol, dave, first, binding(first, alice, k, ts,
				true, true));
		Map<String, ContactId> contacts =
				dave.manager.getMemberContacts(first);
		assertEquals(dave.contactIdOf(alice),
				contacts.get(toHexString(aliceKey)));
		assertTrue(dave.manager.getMemberMlDsaKeys(first)
				.containsKey(toHexString(aliceKey)));

		deliverBinding(alice, dave, first, binding(first, alice, k, ts,
				true, true));
		assertEquals("a binding signed by both keys of the sending contact"
				+ " is taken", dave.contactIdOf(alice), dave.manager
				.getMemberContacts(first).get(toHexString(k)));
	}

	@Test
	public void accountKeysAreRefusedInAGroupWithMemberKeys()
			throws Exception {
		long epoch = alice.manager.getEpoch(first);
		BdfList snapshot = snapshotSignedBy(first, epoch + 1, carol.pub,
				carol.mlDsa, key(alice, first));
		alice.receive(net.packet(carol, alice, snapshot));
		assertEquals(epoch, alice.manager.getEpoch(first));

		long ts = net.now;
		byte[] removed = InviteTestNet.removedSignedInput(first,
				key(dave, first), (int) epoch, (int) epoch + 1, ts);
		alice.receive(net.packet(carol, alice, BdfList.of(34L, first,
				key(dave, first), epoch, epoch + 1, ts,
				InviteTestNet.sign(carol, "org.zerionproject/GROUP_MEMBERSHIP",
						removed))));
		assertTrue(alice.manager.isMember(first, key(dave, first)));

		BdfList identityPost = post(first, epoch, alice.pub, alice.pub,
				alice.mlDsa, new byte[] {5}, net.now);
		dave.receive(net.packet(alice, dave, identityPost));
		assertEquals(0, remotePostsAccepted(dave, first));
	}

	@Test
	public void memberKeyRecordsAreRefusedInAnOlderGroup() throws Exception {
		byte[] older = carol.manager.createLegacyGroup("Older", null)
				.getGroupId();
		for (InviteTestNet.Node n : new InviteTestNet.Node[] {alice, dave}) {
			carol.manager.inviteContactToGroup(older, carol.contactIdOf(n),
					n.pub, n.name);
			net.pump();
			n.manager.acceptInvite(older);
			net.pump();
		}
		assertTrue(dave.manager.isMember(older, alice.pub));
		long epoch = dave.manager.getEpoch(older);
		byte[] k = key(alice, first);
		dave.receive(net.packet(alice, dave, post(older, epoch, k, k,
				mlOf(k), new byte[] {6}, net.now)));
		dave.receive(net.packet(alice, dave, post(older, epoch, alice.pub,
				k, mlOf(k), new byte[] {7}, net.now)));
		assertEquals(0, remotePostsAccepted(dave, older));
		dave.receive(net.packet(alice, dave, post(older, epoch, alice.pub,
				alice.pub, alice.mlDsa, new byte[] {8}, net.now)));
		assertEquals(1, remotePostsAccepted(dave, older));

		deliverBinding(alice, dave, older, binding(older, alice, k,
				net.now, true, true));
		assertEquals(dave.contactIdOf(alice), dave.manager
				.getMemberContacts(older).get(toHexString(alice.pub)));
		assertFalse(containsKey(dave.manager.getMemberContacts(older), k));
	}

	@Test
	public void anAnswerWithABindingIsRefusedByAnOlderGroup()
			throws Exception {
		InviteTestNet.Node erin = net.node("Erin", 5);
		net.connect(carol, erin);
		byte[] olderId = carol.manager.createLegacyGroup("Older", null)
				.getGroupId();
		GroupTrState older = carol.manager.getGroup(olderId);
		assertNotNull(older);
		carol.manager.inviteContactToGroup(olderId, carol.contactIdOf(erin),
				erin.pub, erin.name);
		net.pump();
		long ts = net.now;
		byte[] name = older.getName().getBytes(
				java.nio.charset.StandardCharsets.UTF_8);
		byte[] creatorName = older.getCreatorName().getBytes(
				java.nio.charset.StandardCharsets.UTF_8);
		ByteBuffer b = ByteBuffer.allocate(32 * 3 + 8 + 4 + name.length
				+ 4 + 32 + 4 + creatorName.length);
		b.put(olderId);
		b.put(erin.pub);
		b.put(carol.pub);
		b.putLong(ts);
		b.putInt(name.length);
		b.put(name);
		b.putInt(32);
		b.put(older.getSalt());
		b.putInt(creatorName.length);
		b.put(creatorName);
		byte[] sig = InviteTestNet.sign(erin,
				GroupTrConstants.SIGNING_LABEL_GROUPTR_INVITE_ACCEPT, b.array());
		byte[][] fresh = freshGroupKey();
		BdfList withBinding = BdfList.of(43L, olderId, ts, sig,
				binding(olderId, erin, fresh[0], ts, true, true));
		carol.receive(net.packet(erin, carol, withBinding));
		assertFalse(carol.manager.isMember(olderId, erin.pub));
		carol.receive(net.packet(erin, carol, BdfList.of(43L, olderId, ts,
				sig)));
		assertTrue("the same answer without a binding is taken",
				carol.manager.isMember(olderId, erin.pub));
	}

	@Test
	public void anInvitationMustMatchTheSchemeOfItsGroupId()
			throws Exception {
		InviteTestNet.Node erin = net.node("Erin", 5);
		net.connect(carol, erin);
		byte[] g = carol.manager.createGroup("Third").getGroupId();
		carol.manager.inviteContactToGroup(g, carol.contactIdOf(erin),
				erin.pub, erin.name);
		InviteTestNet.Packet offer = null;
		for (InviteTestNet.Packet p : carol.sent()) {
			if (p.type() == MessageTypes.GROUPTR_INVITE_OFFER
					&& p.to == erin) {
				offer = p;
			}
		}
		assertNotNull(offer);
		assertEquals(9, offer.body.size());
		BdfList stripped = new BdfList(offer.body.subList(0, 8));
		erin.receive(net.packet(carol, erin, stripped));
		assertTrue(erin.manager.getPendingInvites().isEmpty());

		byte[] olderId = carol.manager.createLegacyGroup("Older", null)
				.getGroupId();
		GroupTrState older = carol.manager.getGroup(olderId);
		assertNotNull(older);
		BdfList keyed = BdfList.of(42L, olderId, older.getName(),
				older.getSalt(), older.getCreatorName(), carol.pub,
				net.now, InviteTestNet.sign(carol,
						GroupTrConstants.SIGNING_LABEL_GROUPTR_INVITE_OFFER,
						new byte[] {1}), binding(olderId, carol, carol.pub,
						net.now, true, true));
		erin.receive(net.packet(carol, erin, keyed));
		assertTrue(erin.manager.getPendingInvites().isEmpty());

		erin.receive(net.packet(carol, erin, offer.body));
		assertEquals(1, erin.manager.getPendingInvites().size());
		assertTrue(erin.manager.canAcceptIncognito(g, false));
	}

	@Test
	public void aContactOnAnOlderVersionCannotBeInvited() throws Exception {
		InviteTestNet.Node erin = net.node("Erin", 5);
		net.connect(carol, erin);
		net.connect(alice, erin);
		carol.setVersion(erin,
				MessagingManager.GROUP_MEMBER_KEYS_MIN_VERSION - 1);
		alice.setVersion(erin,
				MessagingManager.GROUP_MEMBER_KEYS_MIN_VERSION - 1);
		assertFalse(carol.manager.canJoinGroup(first,
				carol.contactIdOf(erin)));
		try {
			carol.manager.inviteContactToGroup(first,
					carol.contactIdOf(erin), erin.pub, erin.name);
			fail("an older app was invited to a group with member keys");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.CONTACT_MUST_UPDATE,
					e.getReason());
		}
		carol.manager.setMembersMayInvite(first, true);
		net.pump();
		try {
			alice.manager.inviteContactAsMember(first,
					alice.contactIdOf(erin));
			fail("an older app was invited by a member");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason.CONTACT_MUST_UPDATE,
					e.getReason());
		}
		net.pump();
		assertTrue(deliveredTo(net, erin).isEmpty());

		byte[] older = carol.manager.createLegacyGroup("Older", null)
				.getGroupId();
		assertTrue(carol.manager.canJoinGroup(older,
				carol.contactIdOf(erin)));
		carol.manager.inviteContactToGroup(older, carol.contactIdOf(erin),
				erin.pub, erin.name);
		net.pump();
		assertEquals(1, erin.manager.getPendingInvites().size());
	}

	@Test
	public void memberKeysSurviveARestartAndARestore() throws Exception {
		byte[] before = key(alice, first);
		alice.restart();
		assertArrayEquals(before, key(alice, first));
		assertTrue(alice.manager.isMember(first, before));
		alice.manager.sendGroupPost(first, new byte[] {1}, 0L);
		net.pump();
		assertEquals(1, remotePostsAccepted(dave, first));

		InviteTestNet.Node restored = net.restore(alice);
		assertArrayEquals(before, key(restored, first));
		assertArrayEquals(key(alice, second), key(restored, second));
		restored.manager.sendGroupPost(first, new byte[] {2}, 0L);
		net.pump();
		assertEquals(2, remotePostsAccepted(dave, first));
		dave.manager.sendGroupPost(first, new byte[] {3}, 0L);
		net.pump();
		assertEquals(1, remotePostsAccepted(restored, first));
		assertEquals(dave.contactIdOf(alice), dave.manager
				.getMemberContacts(first).get(toHexString(before)));
	}

	@Test
	public void leavingErasesOnlyThatGroupsKey() throws Exception {
		byte[] keep = key(alice, second);
		alice.manager.leaveGroup(first);
		net.pump();
		assertTrue(empty(alice.namespace(GroupTrKeys.keysNamespace(first))));
		assertTrue(empty(alice.namespace(
				GroupTrKeys.bindingsNamespace(first))));
		assertArrayEquals(keep, key(alice, second));
		assertFalse(carol.manager.isMember(first, keep));
	}

	private int remotePostsFrom(InviteTestNet.Node at, byte[] groupId,
			InviteTestNet.Node from) {
		int n = 0;
		for (InviteTestNet.Packet p : net.delivered) {
			if (p.to == at && p.from == from && p.type() == 32
					&& carries(p.body, groupId)) {
				n++;
			}
		}
		return n;
	}

	private void deliverBinding(InviteTestNet.Node from, InviteTestNet.Node to,
			byte[] groupId, BdfList binding) {
		to.receive(net.packet(from, to, BdfList.of(
				(long) MessageTypes.GROUP_KEY_BINDING, groupId, binding)));
		net.pump();
	}

	private BdfList snapshot(byte[] groupId, long epoch, byte[] signer,
			byte[]... members) {
		return snapshotSignedBy(groupId, epoch, signer, mlOf(signer),
				members);
	}

	private BdfList snapshotSignedBy(byte[] groupId, long epoch,
			byte[] signerEd, byte[] signerMl, byte[]... members) {
		BdfList list = new BdfList();
		ByteBuffer canonical = ByteBuffer.allocate(members.length * 37);
		for (byte[] m : members) {
			list.add(BdfList.of(m, "", 0L, 0L, 1L));
			canonical.put(m);
			canonical.put((byte) 1);
			canonical.putInt(0);
		}
		long ts = net.now;
		byte[] signed = InviteTestNet.snapshotSignedInput(groupId,
				(int) epoch, ts, canonical.array());
		byte[] sig = InviteTestNet.signature(
				"org.zerionproject/GROUP_MEMBER_LIST_SNAPSHOT", signed,
				signerEd, signerMl);
		return BdfList.of(41L, groupId, epoch, ts, list, sig);
	}

	private static boolean empty(Settings s) {
		for (String v : s.values()) {
			if (v != null && !v.isEmpty()) return false;
		}
		return true;
	}
}
