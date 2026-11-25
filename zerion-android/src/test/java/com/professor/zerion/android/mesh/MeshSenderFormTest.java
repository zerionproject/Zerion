package com.professor.zerion.android.mesh;

import org.junit.Before;
import org.junit.Test;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.MeshLinkKeysReceivedEvent;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbCallable;
import org.zerionproject.core.api.db.DbRunnable;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.versioning.ClientVersion;
import org.zerionproject.core.api.versioning.event.ClientVersionUpdatedEvent;
import org.zerionproject.core.crypto.async.AsyncMeshDelivery;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.AsyncPrekeyStore;
import org.zerionproject.core.crypto.async.AsyncSealedSender;
import org.zerionproject.core.system.SystemClock;
import org.zerionproject.core.test.TestUtils;
import org.zerionproject.transport.mesh.MeshForwarder;
import org.zerionproject.transport.mesh.MeshLink;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class MeshSenderFormTest {

	private static final String SIGNED = "signed";
	private static final String KEYED = "keyed";

	private final SecureRandom random = new SecureRandom();
	private final MeshTestSettings settings = new MeshTestSettings();
	private final DatabaseComponent db = mock(DatabaseComponent.class);
	private final ContactManager contacts = mock(ContactManager.class);
	private final MessagingManager messaging = mock(MessagingManager.class);
	private final ContactId contactId = new ContactId(7);
	private final List<byte[]> withoutLinkKey = new ArrayList<>();
	private final List<byte[]> withLinkKey = new ArrayList<>();

	private CryptoComponent crypto;
	private AsyncSealedSender sealer;
	private AsyncMeshDelivery.Identity sender;
	private AsyncPrekeyStore senderStore;
	private AsyncPrekeyBundle contactBundle;
	private AsyncMeshDelivery contactWithoutLinkKey;
	private AsyncMeshDelivery contactWithLinkKey;
	private MeshLinkKeys keys;
	private int announced;
	private byte[] ourPair;
	private byte[] theirPair;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerMeshCryptoTestComponent.create().getCryptoComponent();
		sealer = new AsyncSealedSender(crypto);
		sender = identity(crypto.generateHybridSignatureKeyPair());
		senderStore = new AsyncPrekeyStore(crypto, new MeshTestSettings(),
				new SystemClock());
		KeyPair contactSig = crypto.generateHybridSignatureKeyPair();
		Contact contact = contact(contactSig);
		when(contacts.getContacts())
				.thenReturn(Collections.singletonList(contact));
		when(contacts.getContact(contactId)).thenReturn(contact);
		when(contacts.getContact(any(Transaction.class), any(ContactId.class)))
				.thenReturn(contact);
		when(db.transactionWithResult(anyBoolean(), any()))
				.thenAnswer(i -> ((DbCallable<?, ?>) i.getArgument(1))
						.call(new Transaction(null, true)));
		doAnswer(i -> {
			((DbRunnable<?>) i.getArgument(1))
					.run(new Transaction(null, false));
			return null;
		}).when(db).transaction(anyBoolean(), any());
		when(messaging.getContactClientMinorVersion(any(Transaction.class),
				any(ContactId.class))).thenAnswer(i -> announced);
		when(messaging.sendMeshLinkKeys(any(ContactId.class),
				any(byte[].class), any(byte[].class))).thenAnswer(i -> {
			ourPair = i.getArgument(2);
			return true;
		});
		keys = start();
		AsyncPrekeyStore contactStore = new AsyncPrekeyStore(crypto,
				new MeshTestSettings(), new SystemClock());
		AsyncMeshDelivery.Identity contactIdentity = identity(contactSig);
		AsyncPrekeyStore.SignedPrekey spk = contactStore.getSignedPrekey();
		contactBundle = AsyncPrekeyBundle.create(crypto,
				contactIdentity.sigPub, contactIdentity.sigPriv,
				contactIdentity.agreePub, spk.id, spk.pub, spk.expiry,
				contactStore.generateOneTimePrekeys(8));
		contactWithoutLinkKey = new AsyncMeshDelivery(crypto, sealer,
				contactStore,
				(from, type, payload, ts) -> withoutLinkKey.add(payload),
				contactIdentity, new SystemClock(), null);
		contactWithLinkKey = new AsyncMeshDelivery(crypto, sealer,
				contactStore,
				(from, type, payload, ts) -> withLinkKey.add(payload),
				contactIdentity, new SystemClock(),
				peer -> Arrays.equals(peer, sender.sigPub) ? linkKey() : null);
	}

	@Test
	public void aContactThatAnnouncedVersion17GetsNoSignedEnvelopeLater()
			throws Exception {
		exchangeLinkKeys();
		announce(MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);

		announce(MessagingManager.MESH_LINK_KEYS_MIN_VERSION);

		assertEquals(KEYED, formOf(send("first")));
		announce(0);
		assertEquals(KEYED, formOf(send("second")));
	}

	@Test
	public void aContactThatAnnouncedVersion17AndWithholdsItsKeysGetsNothing()
			throws Exception {
		announce(MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);

		announce(MessagingManager.MESH_LINK_KEYS_MIN_VERSION);

		assertNothingLeaves();
	}

	@Test
	public void aContactThatReturnsOurPairSecretGetsNothing() throws Exception {
		exchangeLinkKeys();
		announce(MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);
		announce(MessagingManager.MESH_LINK_KEYS_MIN_VERSION);

		keys.eventOccurred(new MeshLinkKeysReceivedEvent(contactId,
				bytes(32), ourPair));

		assertNothingLeaves();
	}

	@Test
	public void theKeyedFormIsKeptAfterARestart() throws Exception {
		exchangeLinkKeys();
		announce(MessagingManager.MESH_SENDER_AUTH_V2_MIN_VERSION);
		announced = MessagingManager.MESH_LINK_KEYS_MIN_VERSION;

		keys = start();

		assertEquals(KEYED, formOf(send("after a restart")));
	}

	@Test
	public void aContactThatNeverAnnouncedVersion17GetsTheSignedEnvelope()
			throws Exception {
		exchangeLinkKeys();
		announce(13);
		announce(MessagingManager.MESH_LINK_KEYS_MIN_VERSION);

		assertEquals(SIGNED, formOf(send("as before")));
	}

	private MeshLinkKeys start() {
		return new MeshLinkKeys(mock(EventBus.class), db, settings, contacts,
				messaging, Runnable::run);
	}

	private void exchangeLinkKeys() {
		theirPair = bytes(32);
		keys.eventOccurred(new MeshLinkKeysReceivedEvent(contactId,
				bytes(32), theirPair));
		assertNotNull(ourPair);
	}

	private byte[] linkKey() {
		return MeshPrivateLink.linkKey(ourPair, theirPair);
	}

	private void announce(int minor) {
		announced = minor;
		keys.eventOccurred(new ClientVersionUpdatedEvent(contactId,
				new ClientVersion(MessagingManager.CLIENT_ID,
						MessagingManager.MAJOR_VERSION, minor)));
	}

	private byte[] send(String text) throws Exception {
		List<byte[]> captured = new ArrayList<>();
		sendInto(captured, text);
		assertEquals(1, captured.size());
		return captured.get(0);
	}

	private void assertNothingLeaves() {
		List<byte[]> captured = new ArrayList<>();
		try {
			sendInto(captured, "refused");
			fail("an envelope was sealed");
		} catch (GeneralSecurityException expected) {
		}
		assertTrue(captured.isEmpty());
	}

	private void sendInto(List<byte[]> captured, String text)
			throws GeneralSecurityException {
		AsyncMeshDelivery delivery = new AsyncMeshDelivery(crypto, sealer,
				senderStore, (from, type, payload, ts) -> true, sender,
				new SystemClock(), keys);
		MeshForwarder origin = new MeshForwarder(delivery, random);
		MeshForwarder relay = new MeshForwarder(captured::add, random);
		origin.addLink(new MeshLink() {
			@Override
			public String getId() {
				return "out";
			}

			@Override
			public void broadcast(byte[] frame) {
				relay.onReceive(frame, "in");
			}
		});
		delivery.send(origin, contactBundle, MeshMessageRouter.MESH_TEXT,
				text.getBytes(), 3600L, System.currentTimeMillis(), true);
	}

	private String formOf(byte[] envelope) {
		withoutLinkKey.clear();
		withLinkKey.clear();
		contactWithoutLinkKey.onFrame(envelope);
		if (withoutLinkKey.size() == 1) return SIGNED;
		contactWithLinkKey.onFrame(envelope);
		if (withLinkKey.size() == 1) return KEYED;
		throw new AssertionError("the envelope did not open");
	}

	private AsyncMeshDelivery.Identity identity(KeyPair sig) {
		return new AsyncMeshDelivery.Identity(sig.getPublic().getEncoded(),
				sig.getPrivate(), crypto.generateHybridAgreementKeyPair()
				.getPublic().getEncoded());
	}

	private Contact contact(KeyPair sig) {
		HybridSignaturePublicKey pub = (HybridSignaturePublicKey) sig.getPublic();
		Author author = new Author(new AuthorId(TestUtils.getRandomId()),
				Author.FORMAT_VERSION, "Contact",
				new SignaturePublicKey(pub.getEd25519PublicKey()));
		return new Contact(contactId, author,
				new AuthorId(TestUtils.getRandomId()), null, null, true, true,
				false, pub.getMlDsaPublicKey());
	}

	private byte[] bytes(int n) {
		byte[] b = new byte[n];
		random.nextBytes(b);
		return b;
	}
}
