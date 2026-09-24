package com.professor.zerion.android.mesh;

import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridSignaturePublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.crypto.async.AsyncPrekeyBundle;
import org.zerionproject.core.crypto.async.MeshBundleStore;
import org.zerionproject.core.test.TestUtils;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.event.PrekeyBundleReceivedEvent;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Prekey bundles received over the contact channel are stored only when both
 * signatures verify and the bundle's identity key is the contact's own hybrid
 * identity: a valid bundle for another identity, a tampered bundle, or a
 * bundle from a contact without a post-quantum identity is dropped. Our own
 * bundle goes to a connecting contact at most once an hour.
 */
public class MeshBundleManagerTest {

	private final Random random = new Random(97);
	private final EventBus eventBus = mock(EventBus.class);
	private final MeshBundleStore store = mock(MeshBundleStore.class);
	private final ContactManager contactManager = mock(ContactManager.class);
	private final MeshManager meshManager = mock(MeshManager.class);
	private final MessagingManager messagingManager =
			mock(MessagingManager.class);
	private final ContactId contactId = new ContactId(3);

	private CryptoComponent crypto;
	private KeyPair contactIdentity;
	private EventListener listener;

	@Before
	public void setUp() throws Exception {
		crypto = DaggerMeshCryptoTestComponent.create().getCryptoComponent();
		contactIdentity = crypto.generateHybridSignatureKeyPair();
		when(contactManager.getContact(contactId))
				.thenReturn(contact(contactId, contactIdentity));
		new MeshBundleManager(eventBus, store, crypto, contactManager,
				meshManager, messagingManager, Runnable::run);
		ArgumentCaptor<EventListener> captor =
				ArgumentCaptor.forClass(EventListener.class);
		verify(eventBus).addListener(captor.capture());
		listener = captor.getValue();
	}

	@Test
	public void aContactsOwnValidBundleIsStored() throws Exception {
		byte[] encoded = bundle(contactIdentity).encode();
		listener.eventOccurred(new PrekeyBundleReceivedEvent(contactId,
				encoded));
		verify(store).putContactBundle(contactId.getInt(), encoded);
	}

	@Test
	public void aValidBundleForAnotherIdentityIsRejected() throws Exception {
		KeyPair other = crypto.generateHybridSignatureKeyPair();
		listener.eventOccurred(new PrekeyBundleReceivedEvent(contactId,
				bundle(other).encode()));
		verify(store, never()).putContactBundle(anyInt(), any());
	}

	@Test
	public void aTamperedBundleIsRejected() throws Exception {
		byte[] encoded = bundle(contactIdentity).encode();
		for (int i = 0; i < 40; i++) {
			byte[] tampered = encoded.clone();
			tampered[random.nextInt(tampered.length)] ^=
					(byte) (1 << random.nextInt(8));
			listener.eventOccurred(new PrekeyBundleReceivedEvent(contactId,
					tampered));
		}
		listener.eventOccurred(new PrekeyBundleReceivedEvent(contactId,
				new byte[0]));
		listener.eventOccurred(new PrekeyBundleReceivedEvent(contactId,
				java.util.Arrays.copyOf(encoded, encoded.length - 1)));
		verify(store, never()).putContactBundle(anyInt(), any());
	}

	@Test
	public void aContactWithoutAPostQuantumIdentityGetsNoBundle()
			throws Exception {
		Contact classical = new Contact(contactId,
				contact(contactId, contactIdentity).getAuthor(),
				new AuthorId(TestUtils.getRandomId()), null, null, true,
				false, false, null);
		when(contactManager.getContact(contactId)).thenReturn(classical);
		listener.eventOccurred(new PrekeyBundleReceivedEvent(contactId,
				bundle(contactIdentity).encode()));
		verify(store, never()).putContactBundle(anyInt(), any());
	}

	@Test
	public void ourBundleIsSentOncePerContactPerHour() throws Exception {
		AsyncPrekeyBundle ours = bundle(crypto.generateHybridSignatureKeyPair());
		when(meshManager.publishBundle()).thenReturn(ours);
		listener.eventOccurred(new ContactConnectedEvent(contactId));
		listener.eventOccurred(new ContactConnectedEvent(contactId));
		listener.eventOccurred(new ContactConnectedEvent(contactId));
		verify(messagingManager, times(1)).sendPrekeyBundle(eq(contactId),
				eq(ours.encode()));
		ContactId another = new ContactId(4);
		listener.eventOccurred(new ContactConnectedEvent(another));
		verify(messagingManager, times(1)).sendPrekeyBundle(eq(another),
				eq(ours.encode()));
		verify(meshManager, times(2)).publishBundle();
	}

	@Test
	public void aFailedSendIsNotCountedAsSent() throws Exception {
		AsyncPrekeyBundle ours = bundle(crypto.generateHybridSignatureKeyPair());
		when(meshManager.publishBundle())
				.thenThrow(new org.zerionproject.core.api.db.DbException())
				.thenReturn(ours);
		listener.eventOccurred(new ContactConnectedEvent(contactId));
		listener.eventOccurred(new ContactConnectedEvent(contactId));
		verify(messagingManager, times(1)).sendPrekeyBundle(eq(contactId),
				any());
	}

	private Contact contact(ContactId id, KeyPair identity) {
		HybridSignaturePublicKey pub =
				(HybridSignaturePublicKey) identity.getPublic();
		Author author = new Author(new AuthorId(TestUtils.getRandomId()),
				Author.FORMAT_VERSION, "Contact",
				new SignaturePublicKey(pub.getEd25519PublicKey()));
		return new Contact(id, author, new AuthorId(TestUtils.getRandomId()),
				null, null, true, true, false, pub.getMlDsaPublicKey());
	}

	private AsyncPrekeyBundle bundle(KeyPair identitySig) throws Exception {
		KeyPair identityAgree = crypto.generateHybridAgreementKeyPair();
		KeyPair signedPrekey = crypto.generateHybridAgreementKeyPair();
		List<AsyncPrekeyBundle.OneTimePrekey> oneTime = new ArrayList<>();
		for (int i = 0; i < 2; i++) {
			byte[] id = new byte[AsyncPrekeyBundle.ONE_TIME_PREKEY_ID_BYTES];
			random.nextBytes(id);
			oneTime.add(new AsyncPrekeyBundle.OneTimePrekey(id,
					crypto.generateHybridAgreementKeyPair().getPublic()
							.getEncoded()));
		}
		return AsyncPrekeyBundle.create(crypto,
				identitySig.getPublic().getEncoded(), identitySig.getPrivate(),
				identityAgree.getPublic().getEncoded(), 1L,
				signedPrekey.getPublic().getEncoded(),
				System.currentTimeMillis() / 1000 + 86_400L, oneTime);
	}
}
