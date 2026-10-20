package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.data.BdfList;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.grouptr.GroupKeysKit.binding;
import static org.zerionproject.app.grouptr.GroupKeysKit.freshGroupKey;
import static org.zerionproject.app.grouptr.GroupKeysKit.join;
import static org.zerionproject.app.grouptr.GroupKeysKit.key;
import static org.zerionproject.app.grouptr.GroupKeysKit.mlOf;
import static org.zerionproject.app.grouptr.GroupKeysKit.probeEntry;
import static org.zerionproject.app.grouptr.GroupKeysKit.secretOf;
import static org.zerionproject.app.grouptr.GroupKeysKit.secretSeqOf;
import static org.zerionproject.app.grouptr.GroupKeysKit.sentOfType;
import static org.zerionproject.app.grouptr.GroupKeysKit.signAs;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupSecretTest {

	private final InviteTestNet net = new InviteTestNet();
	private final InviteTestNet.Node carol = net.node("Carol", 1);
	private final InviteTestNet.Node alice = net.node("Alice", 2);
	private final InviteTestNet.Node bob = net.node("Bob", 3);
	private final InviteTestNet.Node dave = net.node("Dave", 4);
	private final InviteTestNet.Node erin = net.node("Erin", 5);

	private int bindingsTo(InviteTestNet.Node from, InviteTestNet.Node to) {
		int n = 0;
		for (InviteTestNet.Packet p : from.sent()) {
			if (p.to == to && p.type() == MessageTypes.GROUP_KEY_BINDING) n++;
		}
		return n;
	}

	private static boolean contains(byte[] haystack, byte[] needle) {
		String h = toHexString(haystack);
		String n = toHexString(needle);
		for (int i = h.indexOf(n); i >= 0; i = h.indexOf(n, i + 1)) {
			if (i % 2 == 0) return true;
		}
		return false;
	}

	private static boolean opens(byte[] secret, BdfList entry) {
		byte[] tag = InviteTestNet.hash(GroupTrKeys.LABEL_PROBE,
				new byte[][] {secret, raw(entry, 0)});
		if (Arrays.equals(tag, raw(entry, 1))) return true;
		try {
			byte[] key = InviteTestNet.hash(GroupTrKeys.LABEL_PROBE_KEY,
					new byte[][] {secret, raw(entry, 0)});
			javax.crypto.Cipher c =
					javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
			c.init(javax.crypto.Cipher.DECRYPT_MODE,
					new javax.crypto.spec.SecretKeySpec(key, "AES"),
					new javax.crypto.spec.GCMParameterSpec(128, new byte[12]));
			c.doFinal(raw(entry, 2));
			return true;
		} catch (java.security.GeneralSecurityException ex) {
			return false;
		}
	}

	private static byte[] raw(BdfList l, int i) {
		try {
			return l.getRaw(i);
		} catch (Exception ex) {
			throw new AssertionError(ex);
		}
	}

	@Test
	public void probeEntriesCannotBeToldFromPadding() throws Exception {
		net.connect(carol, dave);
		net.connect(carol, erin);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, dave);
		join(net, carol, g, erin);
		net.connect(dave, erin);
		dave.versionUpdated(erin, 13);
		List<InviteTestNet.Packet> probes =
				sentOfType(dave, MessageTypes.GROUP_KEY_PROBE);
		assertEquals(1, probes.size());
		BdfList entries = probes.get(0).body.getList(1);
		assertEquals(GroupTrKeyExchange.PROBE_SLOTS, entries.size());
		byte[] daveKey = key(dave, g);
		byte[] daveMl = mlOf(daveKey);
		byte[] when = ByteBuffer.allocate(8).putLong(net.now).array();
		int opened = 0;
		for (int i = 0; i < entries.size(); i++) {
			BdfList e = entries.getList(i);
			assertEquals(3, e.size());
			assertEquals(32, e.getRaw(0).length);
			assertEquals(32, e.getRaw(1).length);
			assertEquals(GroupTrKeys.SEALED_BINDING_BYTES, e.getRaw(2).length);
			for (int j = 0; j < 3; j++) {
				assertFalse(contains(e.getRaw(j), daveKey));
				assertFalse(contains(e.getRaw(j), dave.pub));
				assertFalse(contains(e.getRaw(j), g));
				assertFalse(contains(e.getRaw(j), when));
				assertFalse(contains(e.getRaw(j),
						Arrays.copyOf(daveMl, 64)));
			}
			if (opens(secretOf(dave, g), e)) opened++;
		}
		assertEquals("only the holder of the secret finds the real entry", 1,
				opened);
	}

	@Test
	public void formerHoldersCannotReadALaterJoin() throws Exception {
		InviteTestNet.Node frank = net.node("Frank", 6);
		net.connect(carol, alice);
		net.connect(carol, bob);
		net.connect(carol, dave);
		net.connect(carol, erin);
		net.connect(carol, frank);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, dave);
		join(net, carol, g, alice);
		join(net, carol, g, bob);
		carol.manager.inviteContactToGroup(g, carol.contactIdOf(erin),
				erin.pub, erin.name);
		net.pump();
		erin.manager.declineInvite(g);
		net.pump();
		byte[] aliceSecret = secretOf(alice, g);
		byte[] bobSecret = secretOf(bob, g);
		carol.manager.removeMember(g, key(alice, g));
		net.pump();
		bob.manager.leaveGroup(g);
		net.pump();
		net.connect(frank, alice);
		net.connect(frank, bob);
		net.connect(frank, erin);
		net.connect(frank, dave);
		join(net, carol, g, frank);
		byte[] frankKey = key(frank, g);
		int seen = 0;
		for (InviteTestNet.Packet p : sentOfType(frank,
				MessageTypes.GROUP_KEY_PROBE)) {
			if (p.to != alice && p.to != bob && p.to != erin) continue;
			BdfList entries = p.body.getList(1);
			for (int i = 0; i < entries.size(); i++) {
				BdfList e = entries.getList(i);
				seen++;
				for (byte[] s : new byte[][] {aliceSecret, bobSecret, g}) {
					assertFalse("a former holder read a later join probe",
							opens(s, e));
				}
				assertFalse(contains(e.getRaw(2), frankKey));
			}
		}
		assertEquals(3, seen);
		assertEquals(0, bindingsTo(alice, frank));
		assertEquals(0, bindingsTo(bob, frank));
		assertEquals(0, bindingsTo(erin, frank));
		assertEquals(dave.contactIdOf(frank), dave.manager
				.getMemberContacts(g).get(toHexString(frankKey)));
	}

	@Test
	public void theSecretRotatesOnRemovalAndLeaveAndOldOnesAreRefused()
			throws Exception {
		net.connect(carol, alice);
		net.connect(carol, bob);
		net.connect(carol, dave);
		net.connect(carol, erin);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, alice);
		join(net, carol, g, bob);
		join(net, carol, g, dave);
		join(net, carol, g, erin);
		long first = secretSeqOf(dave, g);
		byte[] old = secretOf(dave, g);
		carol.manager.removeMember(g, key(alice, g));
		net.pump();
		long second = secretSeqOf(dave, g);
		assertTrue(second > first);
		assertFalse(Arrays.equals(old, secretOf(dave, g)));
		assertEquals(second, secretSeqOf(erin, g));
		bob.manager.leaveGroup(g);
		net.pump();
		assertTrue(secretSeqOf(dave, g) > second);
		net.connect(dave, erin);
		byte[] nonce = new byte[32];
		nonce[3] = 9;
		BdfList erinBinding = binding(g, erin, key(erin, g), net.now, true,
				true);
		dave.receive(net.packet(erin, dave, BdfList.of(
				(long) MessageTypes.GROUP_KEY_PROBE, BdfList.of(
						probeEntry(old, nonce, erinBinding)))));
		net.pump();
		assertEquals("a probe under an old secret was answered", 0,
				bindingsTo(dave, erin));
		dave.receive(net.packet(erin, dave, BdfList.of(
				(long) MessageTypes.GROUP_KEY_PROBE, BdfList.of(
						probeEntry(secretOf(erin, g), nonce, erinBinding)))));
		net.pump();
		assertEquals(1, bindingsTo(dave, erin));
	}

	private void settle() {
		for (int i = 0; i < 6; i++) {
			for (InviteTestNet.Node n : net.nodes) n.runScheduled();
			net.pump();
		}
	}

	private int probesTo(InviteTestNet.Node from, InviteTestNet.Node to) {
		int n = 0;
		for (InviteTestNet.Packet p : from.sent()) {
			if (p.to == to && p.type() == MessageTypes.GROUP_KEY_PROBE) n++;
		}
		return n;
	}

	private static void dropSecret(InviteTestNet.Node n, byte[] g) {
		org.zerionproject.core.api.settings.Settings s =
				n.settings.get(GroupTrKeys.keysNamespace(g));
		if (s == null) return;
		for (String k : new String[] {"secret", "secretSeq", "departed",
				"previousSecret", "previousUntil"}) {
			s.remove(k);
		}
	}

	private BdfList snapshot(byte[] g, long epoch, BdfList members,
			byte[] signer, BdfList blob) throws Exception {
		long ts = net.now;
		java.io.ByteArrayOutputStream mc = new java.io.ByteArrayOutputStream();
		for (int i = 0; i < members.size(); i++) {
			BdfList m = members.getList(i);
			mc.write(m.getRaw(0));
			mc.write((byte) m.getLong(4).intValue());
			mc.write(ByteBuffer.allocate(4).putInt(m.getLong(3).intValue())
					.array());
		}
		byte[] sig = signAs(signer,
				"org.zerionproject/GROUP_MEMBER_LIST_SNAPSHOT",
				InviteTestNet.snapshotSignedInput(g, (int) epoch, ts,
						mc.toByteArray()));
		return BdfList.of(41L, g, epoch, ts, members, sig, blob);
	}

	private BdfList secretFor(byte[] g, long epoch, BdfList real)
			throws Exception {
		long seq = real.getLong(0);
		byte[] secret = real.getRaw(1);
		BdfList departed = real.getList(2);
		byte[][] entries = new byte[departed.size()][];
		for (int i = 0; i < entries.length; i++) {
			entries[i] = departed.getRaw(i);
		}
		ByteBuffer b = ByteBuffer.allocate(32 + 8 + 8 + 32 + 32 + 1);
		b.put(g);
		b.putLong(seq);
		b.putLong(epoch);
		b.put(secret);
		b.put(InviteTestNet.hash(GroupTrKeys.LABEL_DEPARTED, entries));
		b.put(GroupTrKeys.SECRET_DOMAIN);
		return BdfList.of(seq, secret, departed, signAs(key(carol, g),
				GroupTrKeys.LABEL_SECRET, b.array()));
	}

	@Test
	public void aRotationSendsNoProbesToContactsThatAreNotMembers()
			throws Exception {
		InviteTestNet.Node nina = net.node("Nina", 6);
		net.connect(carol, alice);
		net.connect(carol, bob);
		net.connect(carol, dave);
		net.connect(carol, erin);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, bob);
		join(net, carol, g, alice);
		join(net, carol, g, dave);
		join(net, carol, g, erin);
		settle();
		net.connect(alice, nina);
		net.connect(dave, nina);
		net.connect(erin, nina);
		alice.versionUpdated(nina, 13);
		dave.versionUpdated(nina, 13);
		erin.versionUpdated(nina, 13);
		settle();
		int a = probesTo(alice, nina);
		int d = probesTo(dave, nina);
		int e = probesTo(erin, nina);
		assertEquals(1, a);
		long before = secretSeqOf(dave, g);
		carol.manager.removeMember(g, key(bob, g));
		settle();
		carol.manager.removeMember(g, key(alice, g));
		settle();
		assertTrue(secretSeqOf(dave, g) > before);
		assertEquals("a rotation probed a contact again", d,
				probesTo(dave, nina));
		assertEquals(e, probesTo(erin, nina));
		assertEquals(a, probesTo(alice, nina));
	}

	@Test
	public void aProbeThatCrossedARotationIsStillAnswered() throws Exception {
		net.connect(carol, alice);
		net.connect(carol, dave);
		net.connect(carol, erin);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, alice);
		join(net, carol, g, dave);
		join(net, carol, g, erin);
		byte[] old = secretOf(dave, g);
		carol.manager.removeMember(g, key(alice, g));
		net.pump();
		assertFalse(Arrays.equals(old, secretOf(dave, g)));
		net.connect(dave, erin);
		byte[] nonce = new byte[32];
		nonce[5] = 3;
		BdfList erinBinding = binding(g, erin, key(erin, g), net.now, true,
				true);
		dave.receive(net.packet(erin, dave, BdfList.of(
				(long) MessageTypes.GROUP_KEY_PROBE, BdfList.of(
						probeEntry(old, nonce, erinBinding)))));
		net.pump();
		assertEquals("a probe sealed before the rotation was refused", 1,
				bindingsTo(dave, erin));
	}

	@Test
	public void thePreviousSecretIsOnlyKeptForTwoWeeks() throws Exception {
		net.connect(carol, alice);
		net.connect(carol, dave);
		net.connect(carol, erin);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, alice);
		join(net, carol, g, dave);
		join(net, carol, g, erin);
		byte[] old = secretOf(dave, g);
		carol.manager.removeMember(g, key(alice, g));
		net.pump();
		net.connect(dave, erin);
		dave.clockOverride = net.now + GroupTrKeys.PREVIOUS_SECRET_KEPT_MS;
		byte[] nonce = new byte[32];
		nonce[6] = 4;
		BdfList erinBinding = binding(g, erin, key(erin, g), net.now, true,
				true);
		dave.receive(net.packet(erin, dave, BdfList.of(
				(long) MessageTypes.GROUP_KEY_PROBE, BdfList.of(
						probeEntry(old, nonce, erinBinding)))));
		net.pump();
		assertEquals(0, bindingsTo(dave, erin));
		dave.receive(net.packet(erin, dave, BdfList.of(
				(long) MessageTypes.GROUP_KEY_PROBE, BdfList.of(
						probeEntry(secretOf(erin, g), nonce, erinBinding)))));
		net.pump();
		assertEquals(1, bindingsTo(dave, erin));
	}

	@Test
	public void aLeaveTheCreatorCannotSeeIsRelayedByTheInviter()
			throws Exception {
		InviteTestNet.Node mia = net.node("Mia", 7);
		InviteTestNet.Node xavi = net.node("Xavi", 8);
		net.connect(carol, mia);
		net.connect(carol, dave);
		net.connect(mia, dave);
		net.connect(mia, xavi);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, mia);
		join(net, carol, g, dave);
		carol.manager.setMembersMayInvite(g, true);
		net.pump();
		mia.manager.inviteContactAsMember(g, mia.contactIdOf(xavi));
		net.pump();
		xavi.manager.acceptMemberInvite(g, xavi.contactIdOf(mia));
		settle();
		byte[] xk = key(xavi, g);
		assertTrue(carol.manager.isMember(g, xk));
		assertTrue(dave.manager.isMember(g, xk));
		assertEquals(dave.contactIdOf(mia), dave.manager.getMemberContacts(g)
				.get(toHexString(key(mia, g))));
		long epoch = xavi.manager.getEpoch(g) + 1;
		long ts = net.now;
		ByteBuffer left = ByteBuffer.allocate(77);
		left.put(g);
		left.put(xk);
		left.putInt((int) epoch);
		left.putLong(ts);
		left.put((byte) 0x03);
		byte[] sig = signAs(xk, "org.zerionproject/GROUP_MEMBERSHIP",
				left.array());
		dave.receive(net.packet(mia, dave, BdfList.of(35L, g, xk, epoch, ts,
				sig)));
		assertTrue("a member took a relayed leave",
				dave.manager.isMember(g, xk));
		carol.receive(net.packet(dave, carol, BdfList.of(35L, g, xk, epoch,
				ts, sig)));
		net.pump();
		assertTrue("the creator took a leave relayed by someone else",
				carol.manager.isMember(g, xk));
		long before = secretSeqOf(carol, g);
		xavi.manager.leaveGroup(g);
		settle();
		assertFalse(carol.manager.isMember(g, xk));
		assertTrue("the creator did not rotate after the relayed leave",
				secretSeqOf(carol, g) > before);
		assertEquals(secretSeqOf(carol, g), secretSeqOf(dave, g));
		assertFalse(dave.manager.isMember(g, xk));
	}

	@Test
	public void theCreatorsOwnReAdmissionClearsTheRemovalTag()
			throws Exception {
		InviteTestNet.Node mia = net.node("Mia", 7);
		net.connect(carol, alice);
		net.connect(carol, mia);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, alice);
		join(net, carol, g, mia);
		carol.manager.removeMember(g, key(alice, g));
		settle();
		join(net, carol, g, alice);
		settle();
		alice.manager.leaveGroup(g);
		carol.manager.setMembersMayInvite(g, true);
		settle();
		net.connect(mia, alice);
		settle();
		mia.manager.inviteContactAsMember(g, mia.contactIdOf(alice));
		net.pump();
		alice.manager.acceptMemberInvite(g, alice.contactIdOf(mia));
		settle();
		assertTrue(carol.manager.isMember(g, key(alice, g)));
	}

	@Test
	public void anOlderGroupsSecretReachesAMemberInvitee() throws Exception {
		InviteTestNet.Node mia = net.node("Mia", 7);
		InviteTestNet.Node xavi = net.node("Xavi", 8);
		net.connect(carol, mia);
		net.connect(mia, xavi);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, mia);
		carol.manager.setMembersMayInvite(g, true);
		net.pump();
		mia.manager.inviteContactAsMember(g, mia.contactIdOf(xavi));
		net.pump();
		xavi.manager.acceptMemberInvite(g, xavi.contactIdOf(mia));
		settle();
		assertEquals(1L, secretSeqOf(xavi, g));
		for (InviteTestNet.Node n : new InviteTestNet.Node[] {carol, mia,
				xavi}) {
			dropSecret(n, g);
		}
		List<InviteTestNet.Packet> sent = new ArrayList<>();
		for (InviteTestNet.Packet p : sentOfType(carol, 41)) {
			if (p.to == mia) sent.add(p);
		}
		BdfList real = sent.get(sent.size() - 1).body;
		byte[] fakeSecret = new byte[32];
		fakeSecret[0] = 1;
		BdfList forged = new BdfList(real.subList(0, 6));
		forged.add(BdfList.of(99L, fakeSecret, new BdfList(),
				real.getList(6).getRaw(3)));
		mia.receive(net.packet(carol, mia, forged));
		settle();
		assertEquals(-1L, secretSeqOf(xavi, g));
		long epoch = carol.manager.getEpoch(g);
		carol.restart();
		settle();
		assertEquals(epoch, carol.manager.getEpoch(g));
		assertEquals(1L, secretSeqOf(carol, g));
		assertEquals(1L, secretSeqOf(mia, g));
		assertEquals("the member invitee never got the secret", 1L,
				secretSeqOf(xavi, g));
		assertTrue(Arrays.equals(secretOf(carol, g), secretOf(xavi, g)));
	}

	@Test
	public void anOlderGroupGetsASecretAtStartUp() throws Exception {
		net.connect(carol, dave);
		net.connect(carol, erin);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, dave);
		join(net, carol, g, erin);
		for (InviteTestNet.Node n : new InviteTestNet.Node[] {carol, dave,
				erin}) {
			dropSecret(n, g);
		}
		net.connect(dave, erin);
		assertFalse(dave.manager.getMemberContacts(g)
				.containsKey(toHexString(key(erin, g))));
		carol.restart();
		settle();
		assertEquals(1L, secretSeqOf(carol, g));
		assertTrue(Arrays.equals(secretOf(carol, g), secretOf(dave, g)));
		assertTrue(Arrays.equals(secretOf(carol, g), secretOf(erin, g)));
		assertEquals(dave.contactIdOf(erin), dave.manager
				.getMemberContacts(g).get(toHexString(key(erin, g))));
		byte[] first = secretOf(carol, g);
		carol.restart();
		settle();
		assertTrue(Arrays.equals(first, secretOf(carol, g)));
		assertEquals(1L, secretSeqOf(dave, g));
	}

	@Test
	public void aSecretIsOnlyTakenWithTheSnapshotThatCarriesIt()
			throws Exception {
		net.connect(carol, dave);
		net.connect(carol, erin);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, dave);
		join(net, carol, g, erin);
		List<InviteTestNet.Packet> sent = new ArrayList<>();
		for (InviteTestNet.Packet p : sentOfType(carol, 41)) {
			if (p.to == erin) sent.add(p);
		}
		BdfList real = sent.get(sent.size() - 1).body;
		assertEquals(7, real.size());
		long epoch = real.getLong(2) - 1;
		assertTrue(epoch >= 0);
		BdfList members = real.getList(4);
		BdfList blob = real.getList(6);
		BdfList withoutErin = new BdfList();
		for (int i = 0; i < members.size(); i++) {
			if (!Arrays.equals(members.getList(i).getRaw(0), key(erin, g))) {
				withoutErin.add(members.getList(i));
			}
		}
		byte[] creator = key(carol, g);
		BdfList forEpoch = secretFor(g, epoch, blob);
		long held = erin.manager.getEpoch(g);
		dropSecret(erin, g);
		erin.receive(net.packet(carol, erin,
				snapshot(g, epoch, members, key(dave, g), forEpoch)));
		assertEquals("taken with a snapshot the creator did not sign", -1L,
				secretSeqOf(erin, g));
		erin.receive(net.packet(carol, erin,
				snapshot(g, epoch, withoutErin, creator, forEpoch)));
		assertEquals("taken with a snapshot that does not list the member",
				-1L, secretSeqOf(erin, g));
		erin.receive(net.packet(carol, erin,
				snapshot(g, epoch, members, creator, blob)));
		assertEquals("taken with a snapshot of another epoch", -1L,
				secretSeqOf(erin, g));
		assertEquals(held, erin.manager.getEpoch(g));
		erin.receive(net.packet(carol, erin,
				snapshot(g, epoch, members, creator, forEpoch)));
		assertEquals(secretSeqOf(carol, g), secretSeqOf(erin, g));
	}

	@Test
	public void aMemberCannotBlockSomeoneWithAForgedReport() throws Exception {
		InviteTestNet.Node mia = net.node("Mia", 7);
		net.connect(carol, alice);
		net.connect(carol, dave);
		net.connect(carol, mia);
		net.connect(mia, erin);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, alice);
		join(net, carol, g, dave);
		join(net, carol, g, mia);
		byte[] removed = key(alice, g);
		carol.manager.removeMember(g, removed);
		carol.manager.setMembersMayInvite(g, true);
		net.pump();
		byte[] daveKey = key(dave, g);
		byte[] creatorKey = key(carol, g);
		byte[] fake = freshGroupKey()[0];
		byte[] fakeTag = new byte[32];
		fakeTag[0] = 5;
		byte[] erinTag = InviteTestNet.hash(GroupTrKeys.LABEL_REMOVED_TAG,
				new byte[][] {g, erin.pub});
		long ts = net.now;
		byte[] accept = signAs(fake, GroupTrMemberInvites.LABEL_ACCEPT,
				GroupTrMemberInvites.responseSignedInput(g, fake, daveKey,
						creatorKey, ts, mlOf(fake), fakeTag));
		byte[] base = GroupTrMemberInvites.joinRequestSignedInput(g, daveKey,
				fake, ts, ts, "Xavi", mlOf(fake), accept);
		byte[] reportsHash = InviteTestNet.hash(
				"org.zerionproject/GROUP_REMOVAL_REPORTS",
				new byte[][] {removed, erinTag});
		ByteBuffer b = ByteBuffer.allocate(base.length + 72);
		b.put(base);
		b.putInt(32);
		b.put(fakeTag);
		b.putInt(32);
		b.put(reportsHash);
		byte[] request = signAs(daveKey,
				GroupTrMemberInvites.LABEL_JOIN_REQUEST, b.array());
		carol.receive(net.packet(dave, carol, BdfList.of(52L, g, fake,
				"Xavi", mlOf(fake), ts, accept, ts, request, fakeTag,
				BdfList.of(BdfList.of(removed, erinTag)))));
		net.pump();
		mia.manager.inviteContactAsMember(g, mia.contactIdOf(erin));
		net.pump();
		erin.manager.acceptMemberInvite(g, erin.contactIdOf(mia));
		net.pump();
		assertTrue("a forged report kept someone out",
				carol.manager.isMember(g, key(erin, g)));
	}

	@Test
	public void aRemovedPersonsLeaveRecordDoesNotClearARefusal()
			throws Exception {
		InviteTestNet.Node rita = net.node("Rita", 6);
		net.connect(carol, rita);
		net.connect(carol, dave);
		net.connect(dave, rita);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, dave);
		join(net, carol, g, rita);
		carol.manager.setMembersMayInvite(g, true);
		net.pump();
		byte[] old = key(rita, g);
		assertEquals(dave.contactIdOf(rita), dave.manager.getMemberContacts(g)
				.get(toHexString(old)));
		long epoch = dave.manager.getEpoch(g);
		carol.manager.removeMember(g, old);
		net.pump();
		long ts = net.now;
		ByteBuffer left = ByteBuffer.allocate(77);
		left.put(g);
		left.put(old);
		left.putInt((int) epoch + 1);
		left.putLong(ts);
		left.put((byte) 0x03);
		dave.receive(net.packet(rita, dave, BdfList.of(35L, g, old,
				epoch + 1, ts, signAs(old,
						"org.zerionproject/GROUP_MEMBERSHIP",
						left.array()))));
		net.pump();
		try {
			dave.manager.inviteContactAsMember(g, dave.contactIdOf(rita));
			fail("a removed person's own leave record cleared the refusal");
		} catch (GroupTrAuthException e) {
			assertEquals(GroupTrAuthException.Reason
					.MEMBER_INVITES_UNAVAILABLE, e.getReason());
		}
	}

	@Test
	public void theAccountSigningKeyCopyIsClearedAfterUse() throws Exception {
		net.connect(carol, dave);
		byte[] g = carol.manager.createGroup("G").getGroupId();
		join(net, carol, g, dave);
		carol.manager.removeMember(g, key(dave, g));
		net.pump();
		List<byte[]> handed = new ArrayList<>(carol.handedMlDsaKeys);
		handed.addAll(dave.handedMlDsaKeys);
		assertFalse(handed.isEmpty());
		for (byte[] k : handed) {
			for (byte x : k) assertEquals(0, x);
		}
	}
}
