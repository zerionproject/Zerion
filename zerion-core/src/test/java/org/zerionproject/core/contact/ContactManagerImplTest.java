package org.zerionproject.core.contact;

import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactState;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.crypto.pcs.PqRatchetState;
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

public class ContactManagerImplTest extends BrambleMockTestCase {

	private DatabaseComponent db;
	private KeyManager keyManager;
	private IdentityManager identityManager;
	private PendingContactFactory pendingContactFactory;
	private CryptoComponent crypto;
	private PcsStateManager pcsStateManager;
	private Mode3FullRatchet mode3FullRatchet;

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
		mode3FullRatchet = context.mock(Mode3FullRatchet.class);

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
				pendingContactFactory, crypto, pcsStateManager,
				mode3FullRatchet);
	}

	@Test
	public void testAddContact() throws Exception {
		Transaction txn = new Transaction(null, false);
		Mode3FullState mode3FullState = context.mock(Mode3FullState.class);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(false), withDbCallable(txn));
			oneOf(db).addContact(txn, remote, local, null, verified, false, false, (byte[]) null);
			will(returnValue(contactId));
			oneOf(keyManager).addRotationKeys(txn, contactId, rootKey,
					timestamp, alice, active);
			oneOf(crypto).generateAgreementKeyPair();
			will(returnValue(handshakeKeyPair));
			oneOf(mode3FullRatchet).createInitialState();
			will(returnValue(mode3FullState));
			oneOf(pcsStateManager).initializeMode2State(with(txn),
					with(contactId), with(any(PcsSessionState.class)),
					with(any(PcsSessionState.class)));
			oneOf(pcsStateManager).savePqState(with(txn), with(contactId),
					with(any(PqRatchetState.class)));
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

}
