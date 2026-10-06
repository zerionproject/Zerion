package org.zerionproject.core.contact;

import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.PendingContactState;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.crypto.pcs.PcsStateManager;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.Before;
import org.junit.Test;

import java.util.Collection;
import java.util.Random;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.BASE32_LINK_BYTES;
import static org.zerionproject.core.api.contact.PendingContactState.WAITING_FOR_CONNECTION;
import static org.zerionproject.core.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.zerionproject.core.test.TestUtils.getAgreementPrivateKey;
import static org.zerionproject.core.test.TestUtils.getAgreementPublicKey;
import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getLocalAuthor;
import static org.zerionproject.core.test.TestUtils.getPendingContact;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.util.StringUtils.getRandomBase32String;
import static org.zerionproject.core.util.StringUtils.getRandomString;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ContactManagerImplTest extends BrambleMockTestCase {

	private DatabaseComponent db;
	private KeyManager keyManager;
	private IdentityManager identityManager;
	private PendingContactFactory pendingContactFactory;
	private CryptoComponent crypto;
	private PcsStateManager pcsStateManager;

	private Author remote;
	private LocalAuthor localAuthor;
	private AuthorId local;
	private boolean verified = false, active = true;
	private Contact contact;
	private ContactId contactId;
	private KeyPair handshakeKeyPair;
	private PendingContact pendingContact;
	private SecretKey rootKey;
	private long timestamp;
	private boolean alice;

	private ContactManagerImpl contactManager;

	@Before
	public void setUp() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		db = context.mock(DatabaseComponent.class);
		keyManager = context.mock(KeyManager.class);
		identityManager = context.mock(IdentityManager.class);
		pendingContactFactory = context.mock(PendingContactFactory.class);
		crypto = context.mock(CryptoComponent.class);
		pcsStateManager = context.mock(PcsStateManager.class);

		remote = getAuthor();
		localAuthor = getLocalAuthor();
		local = localAuthor.getId();
		contact = getContact(remote, local, verified);
		contactId = contact.getId();
		handshakeKeyPair = new KeyPair(getAgreementPublicKey(), getAgreementPrivateKey());
		pendingContact = getPendingContact();
		rootKey = getSecretKey();
		timestamp = System.currentTimeMillis();
		alice = new Random().nextBoolean();

		contactManager = new ContactManagerImpl(db, keyManager, identityManager,
				pendingContactFactory, crypto, pcsStateManager);
	}

	@Test
	public void convertingPendingContactRotatesHandshakeKeys()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		PendingContactId p = pendingContact.getId();

		context.checking(new DbExpectations() {{
			oneOf(db).containsContact(txn, remote.getId(), local);
			will(returnValue(false));
			oneOf(db).getPendingContact(txn, p);
			will(returnValue(pendingContact));
			oneOf(db).getContactsByAuthorId(txn, remote.getId());
			will(returnValue(java.util.Collections.emptyList()));
			oneOf(db).getPendingContactOurKeys(txn, p);
			will(returnValue(null));
			oneOf(db).getSettings(txn, ContactManagerImpl.IN_PERSON_NAMESPACE);
			will(returnValue(new org.zerionproject.core.api.settings
					.Settings()));
			oneOf(db).removePendingContact(txn, p);
			oneOf(identityManager).getHandshakeKeys(txn);
			will(returnValue(handshakeKeyPair));
			oneOf(db).addContact(txn, remote, local,
					pendingContact.getPublicKey(), verified, false, false,
					(byte[]) null);
			will(returnValue(contactId));
			oneOf(db).setContactAlias(txn, contactId,
					pendingContact.getAlias());
			oneOf(keyManager).addContact(txn, contactId,
					pendingContact.getPublicKey(), handshakeKeyPair);
			oneOf(keyManager).addRotationKeys(txn, contactId, rootKey,
					timestamp, alice, active);
			oneOf(pcsStateManager).initializePairingRoot(txn, contactId,
					rootKey);
			oneOf(db).getContact(txn, contactId);
			will(returnValue(contact));
			oneOf(identityManager).rotateHybridHandshakeKeys(txn);
		}});

		assertEquals(contactId, contactManager.addContact(txn, p, remote,
				local, rootKey, timestamp, alice, verified, active, null));
	}

	@Test
	public void testAddContact() throws Exception {
		Transaction txn = new Transaction(null, false);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(false), withDbCallable(txn));
			oneOf(db).addContact(txn, remote, local, null, verified, true, false, (byte[]) null);
			will(returnValue(contactId));
			oneOf(keyManager).addRotationKeys(txn, contactId, rootKey,
					timestamp, alice, active);
			oneOf(pcsStateManager).initializePairingRoot(txn, contactId,
					rootKey);
			oneOf(db).getContact(txn, contactId);
			will(returnValue(contact));
		}});

		assertEquals(contactId, contactManager.addContact(remote, local,
				rootKey, timestamp, alice, verified, active));
	}

	@Test
	public void testGetContact() throws Exception {
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getContact(txn, contactId);
			will(returnValue(contact));
		}});

		assertEquals(contact, contactManager.getContact(contactId));
	}

	@Test
	public void testGetContactByAuthor() throws Exception {
		Transaction txn = new Transaction(null, true);
		Collection<Contact> contacts = singletonList(contact);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getContactsByAuthorId(txn, remote.getId());
			will(returnValue(contacts));
		}});

		assertEquals(contact, contactManager.getContact(remote.getId(), local));
	}

	@Test(expected = NoSuchContactException.class)
	public void testGetContactByUnknownAuthor() throws Exception {
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getContactsByAuthorId(txn, remote.getId());
			will(returnValue(emptyList()));
		}});

		contactManager.getContact(remote.getId(), local);
	}

	@Test(expected = NoSuchContactException.class)
	public void testGetContactByUnknownLocalAuthor() throws Exception {
		Transaction txn = new Transaction(null, true);
		Collection<Contact> contacts = singletonList(contact);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getContactsByAuthorId(txn, remote.getId());
			will(returnValue(contacts));
		}});

		contactManager.getContact(remote.getId(), new AuthorId(getRandomId()));
	}

	@Test
	public void testGetContacts() throws Exception {
		Collection<Contact> contacts = singletonList(contact);
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getContacts(txn);
			will(returnValue(contacts));
		}});

		assertEquals(contacts, contactManager.getContacts());
	}

	@Test
	public void testRemoveContact() throws Exception {
		Transaction txn = new Transaction(null, false);

		context.checking(new DbExpectations() {{
			oneOf(db).transaction(with(false), withDbRunnable(txn));
			oneOf(db).getContact(txn, contactId);
			will(returnValue(contact));
			oneOf(db).removeContact(txn, contactId);
		}});

		contactManager.removeContact(contactId);
	}

	@Test
	public void testSetContactAlias() throws Exception {
		Transaction txn = new Transaction(null, false);
		String alias = getRandomString(MAX_AUTHOR_NAME_LENGTH);

		context.checking(new DbExpectations() {{
			oneOf(db).transaction(with(false), withDbRunnable(txn));
			oneOf(db).setContactAlias(txn, contactId, alias);
		}});

		contactManager.setContactAlias(contactId, alias);
	}

	@Test(expected = IllegalArgumentException.class)
	public void testSetContactAliasTooLong() throws Exception {
		Transaction txn = new Transaction(null, false);
		contactManager.setContactAlias(txn, contactId,
				getRandomString(MAX_AUTHOR_NAME_LENGTH + 1));
	}

	@Test
	public void testContactExists() throws Exception {
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).containsContact(txn, remote.getId(), local);
			will(returnValue(true));
		}});

		assertTrue(contactManager.contactExists(remote.getId(), local));
	}

	@Test
	public void anIntroducedContactIsRecordedAsPostQuantum() throws Exception {
		Transaction txn = new Transaction(null, false);
		byte[] mlDsa = getRandomId();

		context.checking(new DbExpectations() {{
			oneOf(db).addContact(txn, remote, local, null, false, true, false,
					mlDsa);
			will(returnValue(contactId));
			allowing(pcsStateManager);
			oneOf(db).getContact(txn, contactId);
			will(returnValue(contact));
		}});

		assertEquals(contactId, contactManager.addContact(txn, remote, local,
				rootKey, false, mlDsa));
	}

	@Test
	public void testGetHandshakeLink() throws Exception {
		Transaction txn = new Transaction(null, true);
		String link = "zerion://" + getRandomBase32String(BASE32_LINK_BYTES);
		KeyPair hybridKeyPair = handshakeKeyPair;

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(identityManager).getHybridHandshakeKeys(txn);
			will(returnValue(hybridKeyPair));
			oneOf(pendingContactFactory).createHandshakeLink(
					hybridKeyPair.getPublic());
			will(returnValue(link));
		}});

		assertEquals(link, contactManager.getHandshakeLink());
	}

	@Test
	public void aContactLinkEnteredAfterTheOwnLinkChangedAddsNothing()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		String shared = "zerion://" + getRandomBase32String(BASE32_LINK_BYTES);
		String current = "zerion://" + getRandomBase32String(BASE32_LINK_BYTES);
		String theirs = "zerion://" + getRandomBase32String(BASE32_LINK_BYTES);

		context.checking(new DbExpectations() {{
			oneOf(db).startTransaction(false);
			will(returnValue(txn));
			oneOf(identityManager).getHybridHandshakeKeys(txn);
			will(returnValue(handshakeKeyPair));
			oneOf(pendingContactFactory).createHandshakeLink(
					handshakeKeyPair.getPublic());
			will(returnValue(current));
			oneOf(db).endTransaction(txn);
		}});

		try {
			contactManager.addPendingContact(theirs, "alias", shared);
			fail();
		} catch (org.zerionproject.core.api.contact
				.OwnLinkChangedException expected) {
		}
	}

	@Test
	public void testDefaultPendingContactState() throws Exception {
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(db).getPendingContacts(txn);
			will(returnValue(singletonList(pendingContact)));
		}});

		Collection<Pair<PendingContact, PendingContactState>> pairs =
				contactManager.getPendingContacts();
		assertEquals(1, pairs.size());
		Pair<PendingContact, PendingContactState> pair =
				pairs.iterator().next();
		assertEquals(pendingContact, pair.getFirst());
		assertEquals(WAITING_FOR_CONNECTION, pair.getSecond());
	}


	@Test
	public void deriveContactKeyUsesTheSharedPairingSecret() throws Exception {
		Transaction txn = new Transaction(null, true);
		byte[] salt = getRandomId();
		SecretKey derived = getSecretKey();
		PcsSessionState state = new PcsSessionState(getSecretKey(), 0, 0,
				rootKey, null, false, 0, null);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(pcsStateManager).loadSendState(txn, contactId);
			will(returnValue(state));
			oneOf(crypto).deriveKey("org.zerionproject.voice/MEMO_WRAP_KEY",
					rootKey, salt);
			will(returnValue(derived));
		}});

		assertEquals(derived, contactManager.deriveContactKey(contactId,
				"org.zerionproject.voice/MEMO_WRAP_KEY", salt));
	}

	@Test
	public void deriveContactKeyWipesTheLoadedRootCopy() throws Exception {
		Transaction txn = new Transaction(null, true);
		byte[] salt = getRandomId();
		SecretKey derived = getSecretKey();
		SecretKey loadedRoot = new SecretKey(rootKey.getBytes().clone());
		SecretKey loadedChain = getSecretKey();
		PcsSessionState state = new PcsSessionState(loadedChain, 0, 0,
				loadedRoot, null, false, 0, null);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(pcsStateManager).loadSendState(txn, contactId);
			will(returnValue(state));
			oneOf(crypto).deriveKey("org.zerionproject.voice/MEMO_WRAP_KEY",
					loadedRoot, salt);
			will(returnValue(derived));
		}});

		contactManager.deriveContactKey(contactId,
				"org.zerionproject.voice/MEMO_WRAP_KEY", salt);
		assertTrue(isZero(loadedRoot.getBytes()));
		assertTrue(isZero(loadedChain.getBytes()));
	}

	private static boolean isZero(byte[] b) {
		int acc = 0;
		for (byte x : b) acc |= x;
		return acc == 0;
	}

	@Test
	public void aPairingWithAnExistingContactReplacesItsKeysAndKeepsIt()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		PendingContactId p = pendingContact.getId();
		org.zerionproject.core.api.settings.Settings none =
				new org.zerionproject.core.api.settings.Settings();

		context.checking(new DbExpectations() {{
			oneOf(db).getPendingContact(txn, p);
			will(returnValue(pendingContact));
			oneOf(db).getContactsByAuthorId(txn, remote.getId());
			will(returnValue(java.util.Collections.singletonList(contact)));
			oneOf(db).getPendingContactOurKeys(txn, p);
			will(returnValue(null));
			oneOf(db).getSettings(txn, ContactManagerImpl.IN_PERSON_NAMESPACE);
			will(returnValue(none));
			oneOf(db).removePendingContact(txn, p);
			oneOf(db).containsContact(txn, remote.getId(), local);
			will(returnValue(true));
			oneOf(db).getContactsByAuthorId(txn, remote.getId());
			will(returnValue(java.util.Collections.singletonList(contact)));
			oneOf(keyManager).addRotationKeys(txn, contactId, rootKey,
					timestamp, alice, true);
			oneOf(db).removePcsSessionState(txn, contactId,
					DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT);
			oneOf(db).removePcsSessionState(txn, contactId,
					DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT_PENDING);
			oneOf(pcsStateManager).initializePairingRoot(txn, contactId,
					rootKey);
			oneOf(db).mergeSettings(with(txn), with(any(
					org.zerionproject.core.api.settings.Settings.class)),
					with(ContactManagerImpl.CONNECTION_KEYS_NAMESPACE));
			oneOf(identityManager).rotateHybridHandshakeKeys(txn);
			never(db).addContact(with(any(Transaction.class)),
					with(any(Author.class)), with(any(AuthorId.class)),
					with(any(org.zerionproject.core.api.crypto.PublicKey.class)),
					with(any(boolean.class)), with(any(boolean.class)),
					with(any(boolean.class)), with(any(byte[].class)));
		}});

		assertEquals(contactId, contactManager.addContact(txn, p, remote,
				local, rootKey, timestamp, alice, verified, active, null));
	}

	@Test
	public void aLinkScannedInPersonVerifiesTheContact() throws Exception {
		Transaction txn = new Transaction(null, false);
		PendingContactId p = pendingContact.getId();
		org.zerionproject.core.api.settings.Settings marked =
				new org.zerionproject.core.api.settings.Settings();
		marked.putBoolean(org.zerionproject.core.util.StringUtils
				.toHexString(p.getBytes()), true);

		context.checking(new DbExpectations() {{
			oneOf(db).getPendingContact(txn, p);
			will(returnValue(pendingContact));
			oneOf(db).getContactsByAuthorId(txn, remote.getId());
			will(returnValue(java.util.Collections.emptyList()));
			oneOf(db).getPendingContactOurKeys(txn, p);
			will(returnValue(null));
			oneOf(db).getSettings(txn, ContactManagerImpl.IN_PERSON_NAMESPACE);
			will(returnValue(marked));
			oneOf(db).mergeSettings(with(txn), with(any(
					org.zerionproject.core.api.settings.Settings.class)),
					with(ContactManagerImpl.IN_PERSON_NAMESPACE));
			oneOf(db).removePendingContact(txn, p);
			oneOf(db).containsContact(txn, remote.getId(), local);
			will(returnValue(false));
			oneOf(identityManager).getHandshakeKeys(txn);
			will(returnValue(handshakeKeyPair));
			oneOf(db).addContact(txn, remote, local,
					pendingContact.getPublicKey(), true, false, false,
					(byte[]) null);
			will(returnValue(contactId));
			oneOf(db).setContactAlias(txn, contactId,
					pendingContact.getAlias());
			oneOf(keyManager).addContact(txn, contactId,
					pendingContact.getPublicKey(), handshakeKeyPair);
			oneOf(keyManager).addRotationKeys(txn, contactId, rootKey,
					timestamp, alice, active);
			oneOf(pcsStateManager).initializePairingRoot(txn, contactId,
					rootKey);
			oneOf(db).getContact(txn, contactId);
			will(returnValue(contact));
			oneOf(identityManager).rotateHybridHandshakeKeys(txn);
		}});

		assertEquals(contactId, contactManager.addContact(txn, p, remote,
				local, rootKey, timestamp, alice, false, active, null));
	}

	@Test(expected = NoSuchContactException.class)
	public void deriveContactKeyFailsWithoutAPairingSecret() throws Exception {
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(pcsStateManager).loadSendState(txn, contactId);
			will(returnValue(null));
		}});

		contactManager.deriveContactKey(contactId,
				"org.zerionproject.voice/MEMO_WRAP_KEY", getRandomId());
	}
}
