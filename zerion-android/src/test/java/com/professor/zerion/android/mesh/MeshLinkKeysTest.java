package com.professor.zerion.android.mesh;

import org.junit.Before;
import org.junit.Test;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.messaging.UnsignedFormsMemory;
import org.zerionproject.app.api.messaging.event.MeshLinkKeysReceivedEvent;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.event.ContactConnectionKeysEvent;
import org.zerionproject.core.api.contact.event.ContactKeyRenewalEvent;
import org.zerionproject.core.api.contact.event.ContactRemovedEvent;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.PostQuantumConstants;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbCallable;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.versioning.ClientVersion;
import org.zerionproject.core.api.versioning.event.ClientVersionUpdatedEvent;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MeshLinkKeysTest {

	private static final class Sent {

		private final ContactId to;
		private final byte[] advertKey;
		private final byte[] pairSecret;

		private Sent(ContactId to, byte[] advertKey, byte[] pairSecret) {
			this.to = to;
			this.advertKey = advertKey;
			this.pairSecret = pairSecret;
		}
	}

	private final Settings stored = new Settings();
	private final DatabaseComponent db = mock(DatabaseComponent.class);
	private final SettingsManager settings = mock(SettingsManager.class);
	private final Map<ContactId, Integer> versions = new HashMap<>();
	private final Map<ContactId, byte[]> identities = new HashMap<>();
	private final ContactManager contacts = mock(ContactManager.class);
	private final MessagingManager messaging = mock(MessagingManager.class);
	private final Map<ContactId, Contact> known = new HashMap<>();
	private final List<Sent> sent = new ArrayList<>();
	private final ContactId alice = new ContactId(1);
	private final ContactId bob = new ContactId(2);
	private boolean reachable = true;
	private UnsignedFormsMemory memory;
	private MeshLinkKeys keys;

	@Before
	public void setUp() throws Exception {
		when(settings.getSettings(anyString())).thenAnswer(i -> {
			Settings copy = new Settings();
			copy.putAll(stored);
			return copy;
		});
		doAnswer(i -> {
			stored.putAll((Settings) i.getArgument(0));
			return null;
		}).when(settings).mergeSettings(any(Settings.class), anyString());
		when(settings.getSettings(any(Transaction.class), anyString()))
				.thenAnswer(i -> {
					Settings copy = new Settings();
					copy.putAll(stored);
					return copy;
				});
		doAnswer(i -> {
			stored.putAll((Settings) i.getArgument(1));
			return null;
		}).when(settings).mergeSettings(any(Transaction.class),
				any(Settings.class), anyString());
		when(contacts.getContacts())
				.thenAnswer(i -> new ArrayList<>(known.values()));
		when(contacts.getContact(any(ContactId.class))).thenAnswer(i -> {
			Contact c = known.get((ContactId) i.getArgument(0));
			if (c == null) throw new NoSuchContactException();
			return c;
		});
		when(messaging.sendMeshLinkKeys(any(ContactId.class),
				any(byte[].class), any(byte[].class))).thenAnswer(i -> {
			if (!reachable) return false;
			sent.add(new Sent(i.getArgument(0), i.getArgument(1),
					i.getArgument(2)));
			return true;
		});
		when(contacts.getContacts(any(Transaction.class)))
				.thenAnswer(i -> new ArrayList<>(known.values()));
		when(contacts.getContact(any(Transaction.class),
				any(ContactId.class))).thenAnswer(i -> {
			Contact c = known.get((ContactId) i.getArgument(1));
			if (c == null) throw new NoSuchContactException();
			return c;
		});
		when(db.transactionWithResult(anyBoolean(), any()))
				.thenAnswer(i -> ((DbCallable<?, ?>) i.getArgument(1))
						.call(new Transaction(null, true)));
		doAnswer(i -> {
			((DbRunnable<?>) i.getArgument(1))
					.run(new Transaction(null, false));
			return null;
		}).when(db).transaction(anyBoolean(), any());
		when(messaging.getContactClientMinorVersion(any(Transaction.class),
				any(ContactId.class))).thenAnswer(i -> {
			Integer v = versions.get((ContactId) i.getArgument(1));
			return v == null ? 0 : v;
		});
		add(alice);
		add(bob);
		keys = start();
	}

	private MeshLinkKeys start() {
		memory = new UnsignedFormsMemory(db, settings, contacts, messaging);
		return new MeshLinkKeys(mock(EventBus.class), settings, contacts,
				messaging, memory, Runnable::run);
	}

	@Test
	public void eachContactIsSentTheSameAdvertKeyAndItsOwnPairSecret() {
		keys.eventOccurred(new ContactConnectedEvent(alice));
		keys.eventOccurred(new ContactConnectedEvent(bob));

		assertEquals(2, sent.size());
		assertArrayEquals(sent.get(0).advertKey, sent.get(1).advertKey);
		assertArrayEquals(keys.ownAdvertKey(), sent.get(0).advertKey);
		assertFalse(Arrays.equals(sent.get(0).pairSecret,
				sent.get(1).pairSecret));
		assertFalse(Arrays.equals(sent.get(0).pairSecret,
				sent.get(0).advertKey));
	}

	@Test
	public void theKeysAreNotSentAgainOnEveryConnection() {
		keys.eventOccurred(new ContactConnectedEvent(alice));
		keys.eventOccurred(new ContactConnectedEvent(alice));

		assertEquals(1, sent.size());
	}

	@Test
	public void aContactOnAnEarlierVersionIsTriedAgainLater() {
		reachable = false;
		keys.eventOccurred(new ContactConnectedEvent(alice));
		assertEquals(0, sent.size());

		reachable = true;
		keys.eventOccurred(new ContactConnectedEvent(alice));

		assertEquals(1, sent.size());
	}

	@Test
	public void aContactBecomesAPeerOnceBothSidesKeysAreHeld() {
		assertTrue(keys.peers().isEmpty());
		byte[] theirAdvert = bytes();
		byte[] theirPair = bytes();

		keys.eventOccurred(new MeshLinkKeysReceivedEvent(alice, theirAdvert,
				theirPair));

		assertEquals("receiving a contact's keys answers with ours", 1,
				sent.size());
		List<MeshPeer> peers = keys.peers();
		assertEquals(1, peers.size());
		assertEquals(alice.getInt(), peers.get(0).contact);
		assertArrayEquals(theirAdvert, peers.get(0).advertKey);
		assertArrayEquals(MeshPrivateLink.linkKey(sent.get(0).pairSecret,
				theirPair), peers.get(0).linkKey);
		assertEquals(MeshPrivateLink.compare(sent.get(0).pairSecret,
				theirPair) < 0, peers.get(0).keepsOwnDial);
	}

	@Test
	public void keysOfTheWrongLengthOrFromAnUnknownContactAreIgnored() {
		keys.eventOccurred(new MeshLinkKeysReceivedEvent(alice,
				new byte[31], bytes()));
		keys.eventOccurred(new MeshLinkKeysReceivedEvent(new ContactId(99),
				bytes(), bytes()));

		assertTrue(keys.peers().isEmpty());
	}

	@Test
	public void removingAContactReplacesTheAdvertKeyForTheOthers()
			throws Exception {
		keys.eventOccurred(new MeshLinkKeysReceivedEvent(alice, bytes(),
				bytes()));
		keys.eventOccurred(new MeshLinkKeysReceivedEvent(bob, bytes(),
				bytes()));
		byte[] before = keys.ownAdvertKey();
		assertNotNull(before);
		assertEquals(2, keys.peers().size());
		byte[] bobsPair = pairSentTo(bob);
		sent.clear();
		Contact removed = known.remove(alice);

		keys.removingContact(new Transaction(null, false), removed);
		keys.eventOccurred(new ContactRemovedEvent(alice));

		byte[] after = keys.ownAdvertKey();
		assertFalse("the removed contact still recognises this device",
				Arrays.equals(before, after));
		assertEquals(1, keys.peers().size());
		assertEquals(bob.getInt(), keys.peers().get(0).contact);
		assertEquals(1, sent.size());
		assertEquals(bob, sent.get(0).to);
		assertArrayEquals(after, sent.get(0).advertKey);
		assertArrayEquals("the pair secret with a remaining contact is kept",
				bobsPair, sent.get(0).pairSecret);
	}

	@Test
	public void aContactThatSendsChangedKeysIsAnsweredWithOurs() {
		keys.eventOccurred(new ContactConnectedEvent(alice));
		assertEquals(1, sent.size());
		stored.put(MeshLinkKeys.SENT_AT + alice.getInt(), "1");

		keys.eventOccurred(new MeshLinkKeysReceivedEvent(alice, bytes(),
				bytes()));

		assertEquals("a contact that lost our keys is not sent them again",
				2, sent.size());
		assertArrayEquals(sent.get(0).pairSecret, sent.get(1).pairSecret);
	}

	@Test
	public void theSameKeysAgainDrawNoAnswer() {
		byte[] theirAdvert = bytes();
		byte[] theirPair = bytes();
		keys.eventOccurred(new MeshLinkKeysReceivedEvent(alice, theirAdvert,
				theirPair));
		assertEquals(1, sent.size());

		keys.eventOccurred(new MeshLinkKeysReceivedEvent(alice, theirAdvert,
				theirPair));

		assertEquals(1, sent.size());
	}

	@Test
	public void aContactCannotClaimOurOwnAdvertKey() {
		byte[] ours = keys.ownAdvertKey();
		assertNotNull(ours);

		keys.eventOccurred(new MeshLinkKeysReceivedEvent(alice, ours,
				bytes()));

		assertTrue(keys.peers().isEmpty());
	}

	@Test
	public void aNewContactInheritsNoKeysLeftUnderItsNumber()
			throws Exception {
		keys.eventOccurred(new MeshLinkKeysReceivedEvent(alice, bytes(),
				bytes()));
		assertEquals(1, keys.peers().size());

		keys.addingContact(new Transaction(null, false), known.get(alice));

		assertTrue(keys.peers().isEmpty());
	}

	@Test
	public void theLinkKeyOfAContactIsKnownOnceBothPairSecretsAreHeld()
			throws Exception {
		assertNull(keys.linkKeyFor(identities.get(alice)));
		assertNull(keys.linkKeyFor(alice));
		byte[] theirPair = bytes();

		keys.eventOccurred(new MeshLinkKeysReceivedEvent(alice, bytes(),
				theirPair));

		byte[] expected = MeshPrivateLink.linkKey(pairSentTo(alice),
				theirPair);
		assertArrayEquals(expected, keys.linkKeyFor(alice));
		assertArrayEquals(expected, keys.linkKeyFor(identities.get(alice)));
		assertNull(keys.linkKeyFor(identities.get(bob)));
		assertNull(keys.linkKeyFor(bytes(
				PostQuantumConstants.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES)));
	}

	@Test
	public void aContactIsSentKeyedEnvelopesOnlyFromMessagingVersion17()
			throws Exception {
		byte[] expected = exchangeWith(alice);

		versions.put(alice, MessagingManager.MESH_LINK_KEYS_MIN_VERSION);
		assertNull(keys.sendingLinkKeyFor(identities.get(alice)));

		versions.put(alice,
				MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);
		assertArrayEquals(expected,
				keys.sendingLinkKeyFor(identities.get(alice)));
	}

	@Test
	public void aContactOnVersion17WithoutALinkKeyIsSentNoSignedEnvelope() {
		versions.put(bob, MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);

		assertRefused(identities.get(bob));
	}

	@Test
	public void aContactThatAnnouncedVersion17StaysOnKeyedEnvelopes()
			throws Exception {
		byte[] expected = exchangeWith(alice);
		announce(alice, MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);

		announce(alice, MessagingManager.MESH_LINK_KEYS_MIN_VERSION);

		assertTrue(keys.unsignedOnly(alice));
		assertArrayEquals(expected,
				keys.sendingLinkKeyFor(identities.get(alice)));
		announce(alice, 0);
		assertTrue(keys.unsignedOnly(alice));
		assertArrayEquals(expected,
				keys.sendingLinkKeyFor(identities.get(alice)));
	}

	@Test
	public void aContactThatAnnouncedVersion17AndHoldsNoLinkKeyIsNotSentTo()
			throws Exception {
		announce(bob, MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);

		announce(bob, MessagingManager.MESH_LINK_KEYS_MIN_VERSION);

		assertTrue(keys.unsignedOnly(bob));
		assertRefused(identities.get(bob));
	}

	@Test
	public void aContactThatReturnsOurPairSecretIsSentNoSignedEnvelope()
			throws Exception {
		exchangeWith(alice);
		announce(alice, MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);
		announce(alice, MessagingManager.MESH_LINK_KEYS_MIN_VERSION);

		keys.eventOccurred(new MeshLinkKeysReceivedEvent(alice, bytes(),
				pairSentTo(alice)));

		assertNull(keys.linkKeyFor(alice));
		assertRefused(identities.get(alice));
	}

	@Test
	public void whatAContactAnnouncedIsStillKnownAfterARestart()
			throws Exception {
		byte[] expected = exchangeWith(alice);
		announce(alice, MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);
		versions.put(alice, MessagingManager.MESH_LINK_KEYS_MIN_VERSION);

		keys = start();

		assertTrue(keys.unsignedOnly(alice));
		assertArrayEquals(expected,
				keys.sendingLinkKeyFor(identities.get(alice)));
	}

	@Test
	public void removingAContactForgetsWhatItAnnounced() throws Exception {
		exchangeWith(alice);
		announce(alice, MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);
		assertTrue(stored.getBoolean(
				UnsignedFormsMemory.UNSIGNED_ONLY + alice.getInt(), false));

		keys.removingContact(new Transaction(null, false), known.get(alice));
		memory.removingContact(new Transaction(null, false), known.get(alice));

		assertFalse(stored.getBoolean(
				UnsignedFormsMemory.UNSIGNED_ONLY + alice.getInt(), false));
		versions.put(alice, MessagingManager.MESH_LINK_KEYS_MIN_VERSION);
		assertFalse(keys.unsignedOnly(alice));
		assertNull(keys.sendingLinkKeyFor(identities.get(alice)));
	}

	@Test
	public void aNewContactInheritsNothingAnnouncedUnderItsNumber()
			throws Exception {
		announce(alice, MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);
		versions.put(alice, 0);

		keys.addingContact(new Transaction(null, false), known.get(alice));
		memory.addingContact(new Transaction(null, false), known.get(alice));

		assertFalse(keys.unsignedOnly(alice));
	}

	@Test
	public void newKeysForAContactKeepWhatItAnnounced() throws Exception {
		byte[] expected = exchangeWith(alice);
		announce(alice, MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);

		for (Event e : new Event[] {new ContactConnectionKeysEvent(alice,
				false), new ContactKeyRenewalEvent(alice, false)}) {
			memory.eventOccurred(e);
			keys.eventOccurred(e);
		}
		announce(alice, MessagingManager.MESH_LINK_KEYS_MIN_VERSION);

		assertTrue(keys.unsignedOnly(alice));
		assertArrayEquals(expected,
				keys.sendingLinkKeyFor(identities.get(alice)));
	}

	@Test
	public void theVersionHeldWhenTheDatabaseOpensIsRemembered()
			throws Exception {
		versions.put(alice, MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);
		versions.put(bob, MessagingManager.MESH_LINK_KEYS_MIN_VERSION);

		memory.onDatabaseOpened(new Transaction(null, false));

		versions.put(alice, MessagingManager.MESH_LINK_KEYS_MIN_VERSION);
		assertTrue(keys.unsignedOnly(alice));
		assertFalse(keys.unsignedOnly(bob));
	}

	@Test
	public void aVersionFirstSeenWhileSendingIsRemembered() throws Exception {
		byte[] expected = exchangeWith(alice);
		versions.put(alice, MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);
		assertArrayEquals(expected,
				keys.sendingLinkKeyFor(identities.get(alice)));

		versions.put(alice, MessagingManager.MESH_LINK_KEYS_MIN_VERSION);

		assertArrayEquals(expected,
				keys.sendingLinkKeyFor(identities.get(alice)));
	}

	@Test
	public void aContactThatNeverAnnouncedVersion17IsSentTheSignedForm()
			throws Exception {
		exchangeWith(alice);
		announce(alice, 13);
		announce(alice, MessagingManager.MESH_LINK_KEYS_MIN_VERSION);
		memory.onDatabaseOpened(new Transaction(null, false));

		assertFalse(keys.unsignedOnly(alice));
		assertNull(keys.sendingLinkKeyFor(identities.get(alice)));
		assertNull(stored.get(
				UnsignedFormsMemory.UNSIGNED_ONLY + alice.getInt()));
	}

	@Test
	public void anotherClientOrMajorVersionDoesNotCount() throws Exception {
		memory.eventOccurred(new ClientVersionUpdatedEvent(alice,
				new ClientVersion(new ClientId("org.zerionproject.other"),
						MessagingManager.MAJOR_VERSION,
						MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION)));
		memory.eventOccurred(new ClientVersionUpdatedEvent(alice,
				new ClientVersion(MessagingManager.CLIENT_ID,
						MessagingManager.MAJOR_VERSION + 1,
						MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION)));

		assertFalse(keys.unsignedOnly(alice));
	}

	@Test
	public void anAnnouncementOfARemovedContactIsNotKept() {
		known.remove(alice);

		memory.eventOccurred(new ClientVersionUpdatedEvent(alice,
				new ClientVersion(MessagingManager.CLIENT_ID,
						MessagingManager.MAJOR_VERSION,
						MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION)));

		assertNull(stored.get(
				UnsignedFormsMemory.UNSIGNED_ONLY + alice.getInt()));
	}

	@Test
	public void aDatabaseFailureSendsNoSignedEnvelope() throws Exception {
		exchangeWith(alice);
		doThrow(new DbException()).when(db)
				.transactionWithResult(anyBoolean(), any());

		assertRefused(identities.get(alice));
	}

	@Test
	public void anIdentityThatIsNoContactIsSentNoSignedEnvelope() {
		assertRefused(bytes(
				PostQuantumConstants.HYBRID_SIGNATURE_PUBLIC_KEY_BYTES));
	}

	private byte[] exchangeWith(ContactId c) {
		byte[] theirPair = bytes();
		keys.eventOccurred(new MeshLinkKeysReceivedEvent(c, bytes(),
				theirPair));
		return MeshPrivateLink.linkKey(pairSentTo(c), theirPair);
	}

	private void announce(ContactId c, int minor) {
		versions.put(c, minor);
		ClientVersionUpdatedEvent e = new ClientVersionUpdatedEvent(c,
				new ClientVersion(MessagingManager.CLIENT_ID,
						MessagingManager.MAJOR_VERSION, minor));
		memory.eventOccurred(e);
		keys.eventOccurred(e);
	}

	private void assertRefused(byte[] identity) {
		try {
			keys.sendingLinkKeyFor(identity);
			fail("a signed envelope would be sent");
		} catch (GeneralSecurityException expected) {
		}
	}

	private byte[] pairSentTo(ContactId c) {
		for (Sent s : sent) {
			if (s.to.equals(c)) return s.pairSecret;
		}
		throw new AssertionError("nothing sent");
	}

	private void add(ContactId id) {
		Contact c = mock(Contact.class);
		when(c.getId()).thenReturn(id);
		byte[] ed25519 = bytes();
		byte[] mlDsa = bytes(PostQuantumConstants.ML_DSA_65_PUBLIC_KEY_BYTES);
		PublicKey pub = mock(PublicKey.class);
		when(pub.getEncoded()).thenReturn(ed25519);
		Author author = mock(Author.class);
		when(author.getPublicKey()).thenReturn(pub);
		when(c.getAuthor()).thenReturn(author);
		when(c.getMlDsaSigPublicKey()).thenReturn(mlDsa);
		identities.put(id,
				new HybridSignaturePublicKey(ed25519, mlDsa).getEncoded());
		known.put(id, c);
	}

	private static byte[] bytes() {
		return bytes(32);
	}

	private static byte[] bytes(int n) {
		byte[] b = new byte[n];
		new SecureRandom().nextBytes(b);
		return b;
	}
}
