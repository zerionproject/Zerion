package org.zerionproject.app.grouptr;

import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.messaging.MessageTypes;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePrivateKey;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.crypto.PrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.settings.SettingsManager;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.zerionproject.app.grouptr.GroupKeysKit.binding;
import static org.zerionproject.app.grouptr.GroupKeysKit.bindingWithoutIdentitySig;
import static org.zerionproject.app.grouptr.GroupKeysKit.identitySigLength;
import static org.zerionproject.app.grouptr.GroupKeysKit.join;
import static org.zerionproject.app.grouptr.GroupKeysKit.key;
import static org.zerionproject.app.grouptr.GroupKeysKit.sentOfType;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.StringUtils.toHexString;

public class GroupBindingV2Test {

	private static final int SIG = PostQuantumConstants.HYBRID_SIGNATURE_BYTES;

	private final GroupTrKeys keys = new GroupTrKeys(
			fake(SettingsManager.class), crypto(), fake(ClientHelper.class));
	private final byte[] groupId = filled(32, (byte) 9);
	private final byte[] identity = filled(32, (byte) 1);
	private final byte[] otherIdentity = filled(32, (byte) 2);
	private final Contact contact = contact(1, identity, mlOf(identity));
	private final Contact other =
			contact(2, otherIdentity, mlOf(otherIdentity));
	private final GroupTrKeys.Own own = keys.generate(5L);

	@Test
	public void aBindingWithoutAnIdentitySignatureEncodesAndParses()
			throws Exception {
		GroupTrKeys.Binding b = keys.bind(groupId, own, identity, null, 5L);
		assertEquals(0, b.identitySig.length);
		assertEquals(SIG, b.groupSig.length);
		BdfList list = b.toList();
		assertEquals(5, list.size());
		assertEquals(0, list.getRaw(3).length);
		GroupTrKeys.Binding parsed = GroupTrKeys.Binding.parse(list);
		assertArrayEquals(b.pub, parsed.pub);
		assertArrayEquals(b.mlDsa, parsed.mlDsa);
		assertEquals(5L, parsed.timestamp);
		assertEquals(0, parsed.identitySig.length);
		assertArrayEquals(b.groupSig, parsed.groupSig);

		byte[] encoded = GroupTrKeys.encodeBinding(b);
		assertEquals(GroupTrKeys.BINDING_V2_BYTES, encoded.length);
		GroupTrKeys.Binding decoded = GroupTrKeys.decodeBinding(encoded);
		assertArrayEquals(b.pub, decoded.pub);
		assertEquals(0, decoded.identitySig.length);
		assertArrayEquals(b.groupSig, decoded.groupSig);

		GroupTrKeys.Binding signed = keys.bind(groupId, own, identity,
				identityKey(identity), 5L);
		assertEquals(SIG, signed.identitySig.length);
		assertEquals(GroupTrKeys.BINDING_BYTES,
				GroupTrKeys.encodeBinding(signed).length);
		GroupTrKeys.Binding back = GroupTrKeys.decodeBinding(
				GroupTrKeys.encodeBinding(signed));
		assertArrayEquals(signed.identitySig, back.identitySig);

		for (int bad : new int[] {1, SIG - 1, SIG + 1}) {
			BdfList l = b.toList();
			l.set(3, new byte[bad]);
			try {
				GroupTrKeys.Binding.parse(l);
				fail("accepted an identity signature of " + bad + " bytes");
			} catch (FormatException expected) {
			}
		}
		for (int bad : new int[] {GroupTrKeys.BINDING_V2_BYTES - 1,
				GroupTrKeys.BINDING_V2_BYTES + 1,
				GroupTrKeys.BINDING_BYTES + 1}) {
			try {
				GroupTrKeys.decodeBinding(new byte[bad]);
				fail("decoded " + bad + " bytes");
			} catch (FormatException expected) {
			}
		}
	}

	@Test
	public void aBindingWithoutAnIdentitySignatureIsOnlyGoodForItsContact()
			throws Exception {
		GroupTrKeys.Binding b = keys.bind(groupId, own, identity, null, 5L);
		assertTrue(keys.verify(groupId, b, contact));
		assertFalse("taken from another contact",
				keys.verify(groupId, b, other));
		assertFalse("taken for another group",
				keys.verify(filled(32, (byte) 8), b, contact));
		assertFalse("taken without the contact's ML-DSA key",
				keys.verify(groupId, b, contact(3, identity, null)));

		byte[] wrong = b.groupSig.clone();
		wrong[0] ^= 1;
		assertFalse(keys.verify(groupId, new GroupTrKeys.Binding(b.pub,
				b.mlDsa, b.timestamp, b.identitySig, wrong), contact));
		assertFalse(keys.verify(groupId, new GroupTrKeys.Binding(b.pub,
				b.mlDsa, b.timestamp + 1, b.identitySig, b.groupSig),
				contact));
		assertFalse("the account key bound as a member key",
				keys.verify(groupId, new GroupTrKeys.Binding(identity,
						b.mlDsa, b.timestamp, b.identitySig, b.groupSig),
						contact));
	}

	@Test
	public void aSignedBindingStillNeedsBothSignatures() throws Exception {
		GroupTrKeys.Binding b = keys.bind(groupId, own, identity,
				identityKey(identity), 5L);
		assertTrue(keys.verify(groupId, b, contact));
		assertFalse(keys.verify(groupId, b, other));
		byte[] badIdentity = b.identitySig.clone();
		badIdentity[0] ^= 1;
		assertFalse(keys.verify(groupId, new GroupTrKeys.Binding(b.pub,
				b.mlDsa, b.timestamp, badIdentity, b.groupSig), contact));
		byte[] badGroup = b.groupSig.clone();
		badGroup[0] ^= 1;
		assertFalse(keys.verify(groupId, new GroupTrKeys.Binding(b.pub,
				b.mlDsa, b.timestamp, b.identitySig, badGroup), contact));
		GroupTrKeys.Binding forOther = keys.bind(groupId, own, otherIdentity,
				identityKey(otherIdentity), 5L);
		assertFalse("signed by another account",
				keys.verify(groupId, forOther, contact));
	}

	@Test
	public void sealedBindingsOfBothFormsOpen() throws Exception {
		byte[] secret = filled(32, (byte) 4);
		byte[] nonce = filled(32, (byte) 6);
		GroupTrKeys.Binding plain = keys.bind(groupId, own, identity, null, 5L);
		byte[] sealed = keys.seal(secret, nonce, plain);
		assertEquals(GroupTrKeys.SEALED_BINDING_V2_BYTES, sealed.length);
		assertEquals(GroupTrKeys.sealedBindingBytes(false), sealed.length);
		GroupTrKeys.Binding opened = keys.open(secret, nonce, sealed);
		assertNotNull(opened);
		assertEquals(0, opened.identitySig.length);
		assertArrayEquals(plain.groupSig, opened.groupSig);

		GroupTrKeys.Binding signed = keys.bind(groupId, own, identity,
				identityKey(identity), 5L);
		byte[] sealedSigned = keys.seal(secret, nonce, signed);
		assertEquals(GroupTrKeys.SEALED_BINDING_BYTES, sealedSigned.length);
		assertEquals(GroupTrKeys.sealedBindingBytes(true),
				sealedSigned.length);
		GroupTrKeys.Binding openedSigned = keys.open(secret, nonce,
				sealedSigned);
		assertNotNull(openedSigned);
		assertArrayEquals(signed.identitySig, openedSigned.identitySig);

		assertNull(keys.open(secret, nonce, Arrays.copyOf(sealed,
				sealed.length - 1)));
		assertNull(keys.open(secret, nonce, Arrays.copyOf(sealed,
				sealed.length + 1)));
		assertNull(keys.open(filled(32, (byte) 5), nonce, sealed));
	}

	@Test
	public void theIdentitySignatureIsLeftOutTowardsAnUpdatedContact()
			throws Exception {
		InviteTestNet net = new InviteTestNet();
		InviteTestNet.Node carol = net.node("Carol", 1);
		InviteTestNet.Node mia = net.node("Mia", 2);
		InviteTestNet.Node olga = net.node("Olga", 3);
		net.connect(carol, mia);
		net.connect(carol, olga);
		int older = MessagingManager.GROUP_BINDING_V2_MIN_VERSION - 1;
		carol.setVersion(olga, older);
		olga.setVersion(carol, older);
		byte[] g = carol.manager.createGroup("Club").getGroupId();

		carol.manager.inviteContactToGroup(g, carol.contactIdOf(mia), mia.pub,
				mia.name);
		net.pump();
		BdfList offer = sentOfType(carol, MessageTypes.GROUPTR_INVITE_OFFER)
				.get(0).body;
		assertEquals(0, identitySigLength(offer.getList(8)));
		assertEquals(SIG, offer.getList(8).getRaw(4).length);
		mia.manager.acceptInvite(g);
		net.pump();
		BdfList accept = sentOfType(mia, MessageTypes.GROUPTR_INVITE_ACCEPT)
				.get(0).body;
		assertEquals(0, identitySigLength(accept.getList(4)));
		byte[] miaKey = key(mia, g);
		assertTrue(carol.manager.isMember(g, miaKey));
		assertEquals(carol.contactIdOf(mia), carol.manager
				.getMemberContacts(g).get(toHexString(miaKey)));
		assertEquals(mia.contactIdOf(carol), mia.manager.getMemberContacts(g)
				.get(toHexString(key(carol, g))));

		carol.manager.inviteContactToGroup(g, carol.contactIdOf(olga),
				olga.pub, olga.name);
		net.pump();
		List<InviteTestNet.Packet> offers =
				sentOfType(carol, MessageTypes.GROUPTR_INVITE_OFFER);
		assertEquals(SIG, identitySigLength(offers.get(1).body.getList(8)));
		olga.manager.acceptInvite(g);
		net.pump();
		BdfList olgaAccept = sentOfType(olga,
				MessageTypes.GROUPTR_INVITE_ACCEPT).get(0).body;
		assertEquals(SIG, identitySigLength(olgaAccept.getList(4)));
		byte[] olgaKey = key(olga, g);
		assertTrue(carol.manager.isMember(g, olgaKey));
		assertEquals(carol.contactIdOf(olga), carol.manager
				.getMemberContacts(g).get(toHexString(olgaKey)));
		assertEquals(olga.contactIdOf(carol), olga.manager
				.getMemberContacts(g).get(toHexString(key(carol, g))));
	}

	@Test
	public void memberInvitesAndProbesFollowTheContactsVersion()
			throws Exception {
		InviteTestNet net = new InviteTestNet();
		InviteTestNet.Node carol = net.node("Carol", 1);
		InviteTestNet.Node mia = net.node("Mia", 2);
		InviteTestNet.Node xavi = net.node("Xavi", 3);
		InviteTestNet.Node olga = net.node("Olga", 4);
		net.connect(carol, mia);
		net.connect(mia, xavi);
		net.connect(mia, olga);
		int older = MessagingManager.GROUP_BINDING_V2_MIN_VERSION - 1;
		mia.setVersion(olga, older);
		olga.setVersion(mia, older);
		byte[] g = carol.manager.createGroup("Club").getGroupId();
		join(net, carol, g, mia);
		carol.manager.setMembersMayInvite(g, true);
		net.pump();

		mia.manager.inviteContactAsMember(g, mia.contactIdOf(xavi));
		net.pump();
		BdfList offer = sentOfType(mia,
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER).get(0).body;
		assertEquals(0, identitySigLength(offer.getList(13)));
		xavi.manager.acceptMemberInvite(g, xavi.contactIdOf(mia));
		net.pump();
		BdfList accept = sentOfType(xavi,
				MessageTypes.GROUPTR_MEMBER_INVITE_ACCEPT).get(0).body;
		assertEquals(0, identitySigLength(accept.getList(5)));
		assertTrue(carol.manager.isMember(g, key(xavi, g)));
		assertEquals(mia.contactIdOf(xavi), mia.manager.getMemberContacts(g)
				.get(toHexString(key(xavi, g))));
		assertEquals(xavi.contactIdOf(mia), xavi.manager.getMemberContacts(g)
				.get(toHexString(key(mia, g))));

		mia.manager.inviteContactAsMember(g, mia.contactIdOf(olga));
		net.pump();
		BdfList olderOffer = sentOfType(mia,
				MessageTypes.GROUPTR_MEMBER_INVITE_OFFER).get(1).body;
		assertEquals(SIG, identitySigLength(olderOffer.getList(13)));
		olga.manager.acceptMemberInvite(g, olga.contactIdOf(mia));
		net.pump();
		BdfList olderAccept = sentOfType(olga,
				MessageTypes.GROUPTR_MEMBER_INVITE_ACCEPT).get(0).body;
		assertEquals(SIG, identitySigLength(olderAccept.getList(5)));
		assertTrue(carol.manager.isMember(g, key(olga, g)));
		assertEquals(mia.contactIdOf(olga), mia.manager.getMemberContacts(g)
				.get(toHexString(key(olga, g))));

		net.connect(xavi, olga);
		olga.setVersion(xavi, older);
		xavi.versionUpdated(olga, older);
		net.pump();
		BdfList probe = sentOfType(xavi, MessageTypes.GROUP_KEY_PROBE).get(0)
				.body;
		for (int i = 0; i < probe.getList(1).size(); i++) {
			assertEquals(GroupTrKeys.SEALED_BINDING_BYTES,
					probe.getList(1).getList(i).getRaw(2).length);
		}
		BdfList answer = sentOfType(olga, MessageTypes.GROUP_KEY_BINDING)
				.get(0).body;
		assertEquals(SIG, identitySigLength(answer.getList(2)));
		assertEquals(olga.contactIdOf(xavi), olga.manager.getMemberContacts(g)
				.get(toHexString(key(xavi, g))));
		assertEquals(xavi.contactIdOf(olga), xavi.manager.getMemberContacts(g)
				.get(toHexString(key(olga, g))));

		net.connect(carol, xavi);
		carol.versionUpdated(xavi,
				MessagingManager.GROUP_BINDING_V2_MIN_VERSION);
		net.pump();
		BdfList newer = sentOfType(carol, MessageTypes.GROUP_KEY_PROBE).get(0)
				.body;
		for (int i = 0; i < newer.getList(1).size(); i++) {
			assertEquals(GroupTrKeys.SEALED_BINDING_V2_BYTES,
					newer.getList(1).getList(i).getRaw(2).length);
		}
		BdfList newerAnswer = sentOfType(xavi, MessageTypes.GROUP_KEY_BINDING)
				.get(0).body;
		assertEquals(0, identitySigLength(newerAnswer.getList(2)));
		assertEquals(xavi.contactIdOf(carol), xavi.manager
				.getMemberContacts(g).get(toHexString(key(carol, g))));
		assertEquals(carol.contactIdOf(xavi), carol.manager
				.getMemberContacts(g).get(toHexString(key(xavi, g))));
	}

	@Test
	public void aReceivedBindingWithoutAnIdentitySignatureNamesItsSender()
			throws Exception {
		InviteTestNet net = new InviteTestNet();
		InviteTestNet.Node carol = net.node("Carol", 1);
		InviteTestNet.Node alice = net.node("Alice", 2);
		InviteTestNet.Node bob = net.node("Bob", 3);
		InviteTestNet.Node dave = net.node("Dave", 4);
		net.connect(carol, alice);
		net.connect(carol, bob);
		net.connect(carol, dave);
		net.connect(alice, dave);
		byte[] g = carol.manager.createGroup("First").getGroupId();
		join(net, carol, g, alice);
		join(net, carol, g, bob);
		join(net, carol, g, dave);
		byte[] k = key(bob, g);
		long ts = net.now + 1;
		deliver(net, carol, dave, g, bindingWithoutIdentitySig(g, alice, k,
				ts, true));
		deliver(net, alice, dave, g, bindingWithoutIdentitySig(g, alice, k,
				ts, false));
		deliver(net, alice, dave, g, bindingWithoutIdentitySig(g, bob, k, ts,
				true));
		deliver(net, alice, dave, g, binding(g, alice, k, ts, false, true));
		assertEquals(dave.contactIdOf(alice), dave.manager
				.getMemberContacts(g).get(toHexString(key(alice, g))));
		assertNull(dave.manager.getMemberContacts(g).get(toHexString(k)));

		deliver(net, alice, dave, g, bindingWithoutIdentitySig(g, alice, k,
				ts, true));
		assertEquals("a binding signed by the member key for the contact it"
				+ " came from is taken", dave.contactIdOf(alice),
				dave.manager.getMemberContacts(g).get(toHexString(k)));
	}

	private static void deliver(InviteTestNet net, InviteTestNet.Node from,
			InviteTestNet.Node to, byte[] groupId, BdfList binding) {
		to.receive(net.packet(from, to, BdfList.of(
				(long) MessageTypes.GROUP_KEY_BINDING, groupId, binding)));
		net.pump();
	}

	private static Contact contact(int id, byte[] ed25519,
			byte[] mlDsa) {
		Author author = new Author(new AuthorId(getRandomId()), 1, "Name",
				new SignaturePublicKey(ed25519));
		return new Contact(new ContactId(id), author,
				new AuthorId(getRandomId()), null, null, true, true, false,
				mlDsa);
	}

	private static HybridSignaturePrivateKey identityKey(byte[] ed25519) {
		byte[] mlPriv =
				new byte[PostQuantumConstants.ML_DSA_65_PRIVATE_KEY_BYTES];
		byte[] ml = mlOf(ed25519);
		System.arraycopy(ml, 0, mlPriv, 0, ml.length);
		return new HybridSignaturePrivateKey(ed25519, mlPriv);
	}

	private static byte[] mlOf(byte[] ed25519) {
		return GroupKeysKit.mlOf(ed25519);
	}

	private static byte[] filled(int n, byte v) {
		byte[] b = new byte[n];
		Arrays.fill(b, v);
		return b;
	}

	private static CryptoComponent crypto() {
		return (CryptoComponent) Proxy.newProxyInstance(
				CryptoComponent.class.getClassLoader(),
				new Class<?>[] {CryptoComponent.class}, (p, m, a) -> {
					switch (m.getName()) {
						case "hash":
							return InviteTestNet.hash((String) a[0],
									(byte[][]) a[1]);
						case "generateHybridSignatureKeyPair":
							return InviteTestNet.groupKeyPair();
						case "hybridSign": {
							byte[][] signer =
									InviteTestNet.signerOf((PrivateKey) a[2]);
							if (signer == null) {
								throw new IllegalStateException();
							}
							return InviteTestNet.signature((String) a[0],
									(byte[]) a[1], signer[0], signer[1]);
						}
						case "verifyHybridSignature": {
							HybridSignaturePublicKey k =
									(HybridSignaturePublicKey) a[3];
							return Arrays.equals((byte[]) a[0],
									InviteTestNet.signature((String) a[1],
											(byte[]) a[2],
											k.getEd25519PublicKey(),
											k.getMlDsaPublicKey()));
						}
						default:
							throw new UnsupportedOperationException(
									m.getName());
					}
				});
	}

	private static <T> T fake(Class<T> type) {
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(),
				new Class<?>[] {type}, (p, m, a) -> {
					throw new UnsupportedOperationException(m.getName());
				}));
	}
}
