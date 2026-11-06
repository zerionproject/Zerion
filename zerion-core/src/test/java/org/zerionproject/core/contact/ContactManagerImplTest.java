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
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.contact.request.ContactLinkStore;
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
	private ContactLinkStore linkStore;
	private Clock clock;

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

		linkStore = context.mock(ContactLinkStore.class);
		clock = context.mock(Clock.class);
		contactManager = new ContactManagerImpl(db, keyManager, identityManager,
				pendingContactFactory, crypto, pcsStateManager, linkStore,
				clock);
	}

	@Test
	public void convertingPendingContactRotatesHandshakeKeys()
			throws Exception {
		KeyPair perAdd = new KeyPair(
				new org.zerionproject.core.api.crypto.HybridAgreementPublicKey(
						org.zerionproject.core.test.TestUtils
								.getRandomBytes(1216)),
				new org.zerionproject.core.api.crypto.HybridAgreementPrivateKey(
						org.zerionproject.core.test.TestUtils
								.getRandomBytes(2432)));
		convert(null, perAdd, true);
		convert(new byte[][] {perAdd.getPublic().getEncoded(),
				perAdd.getPrivate().getEncoded()}, perAdd, true);
		for (String other : new String[] {"ZeroID", "permanent", "one-time"}) {
			byte[][] snapshot = {
					org.zerionproject.core.test.TestUtils.getRandomBytes(1216),
					org.zerionproject.core.test.TestUtils.getRandomBytes(2432)};
			convert(snapshot, perAdd, false);
		}
	}

	private void convert(@javax.annotation.Nullable byte[][] snapshot,
			KeyPair perAdd, boolean rotates) throws Exception {
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
			will(returnValue(snapshot));
			if (snapshot != null) {
				oneOf(identityManager).getHybridHandshakeKeys(txn);
				will(returnValue(perAdd));
			}
			oneOf(db).getSettings(txn, PendingContactFlags.IN_PERSON);
			will(returnValue(new org.zerionproject.core.api.settings
					.Settings()));
			oneOf(db).getSettings(txn, PendingContactFlags.RE_ADD);
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
			if (rotates) {
				oneOf(identityManager).rotateHybridHandshakeKeys(txn);
			} else {
				never(identityManager).rotateHybridHandshakeKeys(txn);
			}
		}});

		assertEquals(contactId, contactManager.addContact(txn, p, remote,
				local, rootKey, timestamp, alice, verified, active, null));
		context.assertIsSatisfied();
	}

	@Test
	public void aPendingContactHookSeesTheConversion() throws Exception {
		Transaction txn = new Transaction(null, false);
		PendingContactId p = pendingContact.getId();
		java.util.List<ContactId> seen = new java.util.ArrayList<>();
		contactManager.registerPendingContactHook((t, pc, c) -> {
			assertEquals(p, pc);
			seen.add(c);
		});
		context.checking(new DbExpectations() {{
			oneOf(db).containsContact(txn, remote.getId(), local);
			will(returnValue(false));
			oneOf(db).getPendingContact(txn, p);
			will(returnValue(pendingContact));
			oneOf(db).getContactsByAuthorId(txn, remote.getId());
			will(returnValue(java.util.Collections.emptyList()));
			oneOf(db).getPendingContactOurKeys(txn, p);
			will(returnValue(null));
			oneOf(db).getSettings(txn, PendingContactFlags.IN_PERSON);
			will(returnValue(new org.zerionproject.core.api.settings
					.Settings()));
			oneOf(db).getSettings(txn, PendingContactFlags.RE_ADD);
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
		contactManager.addContact(txn, p, remote, local, rootKey, timestamp,
				alice, verified, active, null);
		assertEquals(singletonList(contactId), seen);
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
			oneOf(db).mergeSettings(with(txn),
					with(any(org.zerionproject.core.api.settings.Settings
							.class)),
					with(ContactManagerImpl.CONNECTION_KEYS_NAMESPACE));
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
	public void aReAddOfAnExistingContactReplacesItsKeysAndKeepsIt()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		PendingContactId p = pendingContact.getId();

		context.checking(new DbExpectations() {{
			existingContactPairing(this, txn, p, none(), marked());
			clears(this, txn, PendingContactFlags.RE_ADD);
			reKeys(this, txn);
		}});

		assertEquals(contactId, contactManager.addContact(txn, p, remote,
				local, rootKey, timestamp, alice, verified, active, null));
	}

	@Test
	public void aReAddReplacesTheKeysWhenThePeerReplacesThemToo()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		PendingContactId p = pendingContact.getId();

		context.checking(new DbExpectations() {{
			existingContactPairing(this, txn, p, none(), marked());
			clears(this, txn, PendingContactFlags.RE_ADD);
			reKeys(this, txn);
		}});

		assertEquals(contactId, contactManager.addContact(txn, p, remote,
				local, rootKey, timestamp, alice, verified, active, null,
				true));
	}

	@Test
	public void aReAddIsRefusedWhenThePeerDoesNotReplaceTheKeys()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		PendingContactId p = pendingContact.getId();

		context.checking(new DbExpectations() {{
			existingContactPairing(this, txn, p, none(), marked());
			clears(this, txn, PendingContactFlags.RE_ADD);
			neverReKeys(this, txn);
		}});

		assertRefused(txn, p, false);
	}

	@Test
	public void aPairingTheUserDidNotAskForLeavesAnExistingContactAlone()
			throws Exception {
		for (Boolean peer : new Boolean[] {null, true, false}) {
			Transaction txn = new Transaction(null, false);
			PendingContactId p = pendingContact.getId();

			context.checking(new DbExpectations() {{
				existingContactPairing(this, txn, p, none(), none());
				neverReKeys(this, txn);
			}});

			assertRefused(txn, p, peer);
			context.assertIsSatisfied();
		}
	}

	@Test
	public void aMarkOfAnEarlierPendingContactWithTheSameIdDoesNotCount()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		PendingContactId p = pendingContact.getId();
		PendingContact earlier = new PendingContact(p,
				pendingContact.getPublicKey(), pendingContact.getAlias(),
				pendingContact.getTimestamp() - 1);
		org.zerionproject.core.api.settings.Settings stale =
				new org.zerionproject.core.api.settings.Settings();
		stale.putBoolean(PendingContactFlags.key(earlier), true);

		context.checking(new DbExpectations() {{
			existingContactPairing(this, txn, p, stale, stale);
			neverReKeys(this, txn);
		}});

		assertRefused(txn, p, null);
	}

	@Test
	public void anExistingContactScannedInPersonIsReKeyedAndVerified()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		PendingContactId p = pendingContact.getId();

		context.checking(new DbExpectations() {{
			existingContactPairing(this, txn, p, marked(), none());
			clears(this, txn, PendingContactFlags.IN_PERSON);
			reKeys(this, txn);
			oneOf(db).setContactVerified(txn, contactId);
		}});

		assertEquals(contactId, contactManager.addContact(txn, p, remote,
				local, rootKey, timestamp, alice, verified, active, null));
	}

	@Test
	public void bothMarksAreReadBeforeTheAddIsRefused()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		PendingContactId p = pendingContact.getId();

		context.checking(new DbExpectations() {{
			existingContactPairing(this, txn, p, marked(), marked());
			clears(this, txn, PendingContactFlags.IN_PERSON);
			clears(this, txn, PendingContactFlags.RE_ADD);
			neverReKeys(this, txn);
		}});

		assertRefused(txn, p, false);
	}

	@Test
	public void removingAPendingContactClearsItsMarks() throws Exception {
		Transaction txn = new Transaction(null, false);
		PendingContactId p = pendingContact.getId();

		context.checking(new DbExpectations() {{
			oneOf(db).getPendingContact(txn, p);
			will(returnValue(pendingContact));
			oneOf(db).getSettings(txn, PendingContactFlags.IN_PERSON);
			will(returnValue(marked()));
			clears(this, txn, PendingContactFlags.IN_PERSON);
			oneOf(db).getSettings(txn, PendingContactFlags.RE_ADD);
			will(returnValue(marked()));
			clears(this, txn, PendingContactFlags.RE_ADD);
			oneOf(db).removePendingContact(txn, p);
		}});

		contactManager.removePendingContact(txn, p);
	}

	@Test
	public void intendsToReplaceKeysReadsEitherMark() throws Exception {
		PendingContactId p = pendingContact.getId();
		org.zerionproject.core.api.settings.Settings[][] cases = {
				{none(), none()}, {marked(), none()}, {none(), marked()}};
		boolean[] expected = {false, true, true};
		for (int i = 0; i < cases.length; i++) {
			Transaction txn = new Transaction(null, true);
			org.zerionproject.core.api.settings.Settings inPerson =
					cases[i][0];
			org.zerionproject.core.api.settings.Settings reAdd = cases[i][1];
			context.checking(new DbExpectations() {{
				oneOf(db).getPendingContact(txn, p);
				will(returnValue(pendingContact));
				oneOf(db).getSettings(txn, PendingContactFlags.IN_PERSON);
				will(returnValue(inPerson));
				allowing(db).getSettings(txn, PendingContactFlags.RE_ADD);
				will(returnValue(reAdd));
			}});
			assertEquals(expected[i],
					contactManager.intendsToReplaceKeys(txn, p));
			context.assertIsSatisfied();
		}
	}

	private void assertRefused(Transaction txn, PendingContactId p,
			@javax.annotation.Nullable Boolean peerReplacesKeys)
			throws Exception {
		try {
			contactManager.addContact(txn, p, remote, local, rootKey,
					timestamp, alice, verified, active, null,
					peerReplacesKeys);
			fail();
		} catch (org.zerionproject.core.api.db.ContactExistsException e) {
			assertEquals(remote, e.getRemoteAuthor());
		}
	}

	private static org.zerionproject.core.api.settings.Settings none() {
		return new org.zerionproject.core.api.settings.Settings();
	}

	private org.zerionproject.core.api.settings.Settings marked() {
		org.zerionproject.core.api.settings.Settings s =
				new org.zerionproject.core.api.settings.Settings();
		s.putBoolean(PendingContactFlags.key(pendingContact), true);
		return s;
	}

	private void clears(DbExpectations e, Transaction txn, String flag)
			throws Exception {
		e.oneOf(db).mergeSettings(e.with(txn), e.with(e.any(
				org.zerionproject.core.api.settings.Settings.class)),
				e.with(flag));
	}

	private void existingContactPairing(DbExpectations e, Transaction txn,
			PendingContactId p,
			org.zerionproject.core.api.settings.Settings inPerson,
			org.zerionproject.core.api.settings.Settings reAdd)
			throws Exception {
		e.oneOf(db).getPendingContact(txn, p);
		e.will(e.returnValue(pendingContact));
		e.allowing(db).getContactsByAuthorId(txn, remote.getId());
		e.will(e.returnValue(java.util.Collections.singletonList(contact)));
		e.oneOf(db).getPendingContactOurKeys(txn, p);
		e.will(e.returnValue(null));
		e.oneOf(db).getSettings(txn, PendingContactFlags.IN_PERSON);
		e.will(e.returnValue(inPerson));
		e.oneOf(db).getSettings(txn, PendingContactFlags.RE_ADD);
		e.will(e.returnValue(reAdd));
		e.oneOf(db).removePendingContact(txn, p);
		e.oneOf(db).containsContact(txn, remote.getId(), local);
		e.will(e.returnValue(true));
		e.never(db).addContact(e.with(e.any(Transaction.class)),
				e.with(e.any(Author.class)), e.with(e.any(AuthorId.class)),
				e.with(e.any(org.zerionproject.core.api.crypto.PublicKey.class)),
				e.with(e.any(boolean.class)), e.with(e.any(boolean.class)),
				e.with(e.any(boolean.class)), e.with(e.any(byte[].class)));
	}

	private void neverReKeys(DbExpectations e, Transaction txn)
			throws Exception {
		e.never(keyManager).addRotationKeys(e.with(e.any(Transaction.class)),
				e.with(e.any(ContactId.class)), e.with(e.any(SecretKey.class)),
				e.with(e.any(long.class)), e.with(e.any(boolean.class)),
				e.with(e.any(boolean.class)));
		e.never(db).removePcsSessionState(e.with(e.any(Transaction.class)),
				e.with(e.any(ContactId.class)), e.with(e.any(int.class)));
		e.never(pcsStateManager).initializePairingRoot(
				e.with(e.any(Transaction.class)), e.with(e.any(ContactId.class)),
				e.with(e.any(SecretKey.class)));
		e.never(identityManager).rotateHybridHandshakeKeys(txn);
		e.never(db).setContactVerified(e.with(e.any(Transaction.class)),
				e.with(e.any(ContactId.class)));
	}

	private void reKeys(DbExpectations e, Transaction txn) throws Exception {
		e.oneOf(keyManager).addRotationKeys(txn, contactId, rootKey,
				timestamp, alice, true);
		e.oneOf(db).removePcsSessionState(txn, contactId,
				DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT);
		e.oneOf(db).removePcsSessionState(txn, contactId,
				DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT_PENDING);
		e.oneOf(pcsStateManager).initializePairingRoot(txn, contactId,
				rootKey);
		e.oneOf(db).mergeSettings(e.with(txn), e.with(e.any(
				org.zerionproject.core.api.settings.Settings.class)),
				e.with(ContactManagerImpl.CONNECTION_KEYS_NAMESPACE));
		e.oneOf(identityManager).rotateHybridHandshakeKeys(txn);
	}

	@Test
	public void aLinkScannedInPersonVerifiesTheContact() throws Exception {
		Transaction txn = new Transaction(null, false);
		PendingContactId p = pendingContact.getId();
		context.checking(new DbExpectations() {{
			oneOf(db).getPendingContact(txn, p);
			will(returnValue(pendingContact));
			oneOf(db).getContactsByAuthorId(txn, remote.getId());
			will(returnValue(java.util.Collections.emptyList()));
			oneOf(db).getPendingContactOurKeys(txn, p);
			will(returnValue(null));
			oneOf(db).getSettings(txn, PendingContactFlags.IN_PERSON);
			will(returnValue(marked()));
			clears(this, txn, PendingContactFlags.IN_PERSON);
			oneOf(db).getSettings(txn, PendingContactFlags.RE_ADD);
			will(returnValue(none()));
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
