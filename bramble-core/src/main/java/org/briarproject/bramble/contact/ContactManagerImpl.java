package org.briarproject.bramble.contact;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.Pair;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.contact.ContactType;
import org.briarproject.bramble.api.contact.PendingContact;
import org.briarproject.bramble.api.contact.PendingContactId;
import org.briarproject.bramble.api.contact.PendingContactState;
import org.briarproject.bramble.api.contact.event.PendingContactStateChangedEvent;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchContactException;
import org.briarproject.bramble.api.db.SecurityDowngradeException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.transport.KeyManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static org.briarproject.bramble.api.contact.PendingContactState.WAITING_FOR_CONNECTION;
import static org.briarproject.bramble.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.briarproject.bramble.util.StringUtils.toUtf8;

@ThreadSafe
@NotNullByDefault
class ContactManagerImpl implements ContactManager, EventListener {

	private final DatabaseComponent db;
	private final KeyManager keyManager;
	private final IdentityManager identityManager;
	private final PendingContactFactory pendingContactFactory;

	private final List<ContactHook> hooks = new CopyOnWriteArrayList<>();
	private final Map<PendingContactId, PendingContactState> states =
			new ConcurrentHashMap<>();

	@Inject
	ContactManagerImpl(DatabaseComponent db,
			KeyManager keyManager,
			IdentityManager identityManager,
			PendingContactFactory pendingContactFactory) {
		this.db = db;
		this.keyManager = keyManager;
		this.identityManager = identityManager;
		this.pendingContactFactory = pendingContactFactory;
	}

	@Override
	public void registerContactHook(ContactHook hook) {
		hooks.add(hook);
	}

	@Override
	public ContactId addContact(Transaction txn, Author remote, AuthorId local,
			SecretKey rootKey, long timestamp, boolean alice, boolean verified,
			boolean active) throws DbException {
		ContactId c = db.addContact(txn, remote, local, null, verified);
		keyManager.addRotationKeys(txn, c, rootKey, timestamp, alice, active);
		Contact contact = db.getContact(txn, c);
		for (ContactHook hook : hooks) hook.addingContact(txn, contact);
		return c;
	}

	@Override
	public ContactId addContact(Transaction txn, PendingContactId p,
			Author remote, AuthorId local, SecretKey rootKey, long timestamp,
			boolean alice, boolean verified, boolean active)
			throws DbException, GeneralSecurityException {
		PendingContact pendingContact = db.getPendingContact(txn, p);
		// Preserve the PQ status when converting pending contact to contact
		boolean postQuantum = pendingContact.isPostQuantum();
		// Check for downgrade attack: if any existing contact with this author
		// used PQ security, the new handshake must also use PQ
		checkForSecurityDowngrade(txn, remote.getId(), postQuantum);
		db.removePendingContact(txn, p);
		states.remove(p);
		PublicKey theirPublicKey = pendingContact.getPublicKey();
		ContactId c = db.addContact(txn, remote, local, theirPublicKey,
				verified, postQuantum);
		String alias = pendingContact.getAlias();
		if (!alias.equals(remote.getName())) db.setContactAlias(txn, c, alias);
		KeyPair ourKeyPair = identityManager.getHandshakeKeys(txn);
		keyManager.addContact(txn, c, theirPublicKey, ourKeyPair);
		keyManager.addRotationKeys(txn, c, rootKey, timestamp, alice, active);
		Contact contact = db.getContact(txn, c);
		for (ContactHook hook : hooks) hook.addingContact(txn, contact);
		return c;
	}

	/**
	 * Checks if adding a contact with the given security level would be a
	 * downgrade attack. If any existing contact with the same author used
	 * post-quantum security, the new contact must also use PQ.
	 *
	 * @throws SecurityDowngradeException if a downgrade is detected
	 */
	private void checkForSecurityDowngrade(Transaction txn, AuthorId remoteId,
			boolean newIsPostQuantum) throws DbException {
		Collection<Contact> existingContacts =
				db.getContactsByAuthorId(txn, remoteId);
		for (Contact existing : existingContacts) {
			if (existing.isPostQuantum() && !newIsPostQuantum) {
				throw new SecurityDowngradeException(remoteId, true, false);
			}
		}
	}

	@Override
	public ContactId addContact(Transaction txn, Author remote, AuthorId local,
			boolean verified) throws DbException {
		ContactId c = db.addContact(txn, remote, local, null, verified);
		Contact contact = db.getContact(txn, c);
		for (ContactHook hook : hooks) hook.addingContact(txn, contact);
		return c;
	}

	@Override
	public ContactId addContact(Author remote, AuthorId local,
			SecretKey rootKey, long timestamp, boolean alice, boolean verified,
			boolean active) throws DbException {
		return db.transactionWithResult(false, txn ->
				addContact(txn, remote, local, rootKey, timestamp, alice,
						verified, active));
	}

	@Override
	public String getHandshakeLink() throws DbException {
		return db.transactionWithResult(true, this::getHandshakeLink);
	}

	@Override
	public String getHandshakeLink(Transaction txn) throws DbException {
		// Default to Zerion (PQ) contact type for backward compatibility
		return getHandshakeLink(txn, ContactType.ZERION);
	}

	@Override
	public String getHandshakeLink(ContactType contactType) throws DbException {
		return db.transactionWithResult(true, txn ->
				getHandshakeLink(txn, contactType));
	}

	@Override
	public String getHandshakeLink(Transaction txn, ContactType contactType)
			throws DbException {
		if (contactType == ContactType.ZERION) {
			// Use hybrid keys for Zerion-to-Zerion post-quantum handshakes.
			// The link contains a 32-byte commitment to the full hybrid key.
			// The full 1,216-byte key is exchanged over Tor during the handshake.
			KeyPair hybridKeyPair = identityManager.getHybridHandshakeKeys(txn);
			if (hybridKeyPair != null) {
				return pendingContactFactory.createHandshakeLink(
						hybridKeyPair.getPublic());
			}
			// If no hybrid keys available, throw exception - no silent fallback
			throw new DbException(new IllegalStateException(
					"Hybrid keys not available for Zerion contact type"));
		} else {
			// Use classical keys for Briar-compatible contacts
			KeyPair keyPair = identityManager.getHandshakeKeys(txn);
			return pendingContactFactory.createHandshakeLink(keyPair.getPublic());
		}
	}

	@Override
	public PendingContact addPendingContact(Transaction txn, String link,
			String alias)
			throws DbException, FormatException, GeneralSecurityException {
		PendingContact p =
				pendingContactFactory.createPendingContact(link, alias);
		AuthorId local = identityManager.getLocalAuthor(txn).getId();
		db.addPendingContact(txn, p, local);
		// For classical (Briar-compatible) links, we have the full X25519
		// public key in the link and can derive transport keys immediately.
		// For hybrid (PQ) links, the link only contains a commitment hash -
		// the full hybrid key exchange happens over Tor during the handshake.
		if (p.isClassical()) {
			KeyPair ourKeyPair = identityManager.getHandshakeKeys(txn);
			keyManager.addPendingContact(txn, p.getId(), p.getPublicKey(),
					ourKeyPair);
		}
		// For hybrid pending contacts, transport keys will be derived later
		// when the full hybrid public key is received over Tor
		return p;
	}

	@Override
	public PendingContact addPendingContact(String link, String alias)
			throws DbException, FormatException, GeneralSecurityException {
		Transaction txn = db.startTransaction(false);
		try {
			PendingContact p = addPendingContact(txn, link, alias);
			db.commitTransaction(txn);
			return p;
		} finally {
			db.endTransaction(txn);
		}
	}

	@Override
	public PendingContact getPendingContact(Transaction txn, PendingContactId p)
			throws DbException {
		return db.getPendingContact(txn, p);
	}

	@Override
	public Collection<Pair<PendingContact, PendingContactState>> getPendingContacts()
			throws DbException {
		return db.transactionWithResult(true, this::getPendingContacts);
	}

	@Override
	public Collection<Pair<PendingContact, PendingContactState>> getPendingContacts(
			Transaction txn)
			throws DbException {
		Collection<PendingContact> pendingContacts = db.getPendingContacts(txn);
		List<Pair<PendingContact, PendingContactState>> pairs =
				new ArrayList<>(pendingContacts.size());
		for (PendingContact p : pendingContacts) {
			PendingContactState state = states.get(p.getId());
			if (state == null) state = WAITING_FOR_CONNECTION;
			pairs.add(new Pair<>(p, state));
		}
		return pairs;
	}

	@Override
	public void removePendingContact(PendingContactId p) throws DbException {
		db.transaction(false, txn -> removePendingContact(txn, p));
	}

	@Override
	public void removePendingContact(Transaction txn, PendingContactId p)
			throws DbException {
		db.removePendingContact(txn, p);
		states.remove(p);
	}

	@Override
	public Contact getContact(ContactId c) throws DbException {
		return db.transactionWithResult(true, txn -> db.getContact(txn, c));
	}

	@Override
	public Contact getContact(Transaction txn, ContactId c) throws DbException {
		return db.getContact(txn, c);
	}

	@Override
	public Contact getContact(AuthorId remoteAuthorId, AuthorId localAuthorId)
			throws DbException {
		return db.transactionWithResult(true, txn ->
				getContact(txn, remoteAuthorId, localAuthorId));
	}

	@Override
	public Contact getContact(Transaction txn, AuthorId remoteAuthorId,
			AuthorId localAuthorId) throws DbException {
		Collection<Contact> contacts =
				db.getContactsByAuthorId(txn, remoteAuthorId);
		for (Contact c : contacts) {
			if (c.getLocalAuthorId().equals(localAuthorId)) {
				return c;
			}
		}
		throw new NoSuchContactException();
	}

	@Override
	public Collection<Contact> getContacts() throws DbException {
		return db.transactionWithResult(true, db::getContacts);
	}

	@Override
	public Collection<Contact> getContacts(Transaction txn) throws DbException {
		return db.getContacts(txn);
	}

	@Override
	public void removeContact(ContactId c) throws DbException {
		db.transaction(false, txn -> removeContact(txn, c));
	}

	@Override
	public void setContactAlias(Transaction txn, ContactId c,
			@Nullable String alias) throws DbException {
		if (alias != null) {
			int aliasLength = toUtf8(alias).length;
			if (aliasLength == 0 || aliasLength > MAX_AUTHOR_NAME_LENGTH)
				throw new IllegalArgumentException();
		}
		db.setContactAlias(txn, c, alias);
	}

	@Override
	public void setContactAlias(ContactId c, @Nullable String alias)
			throws DbException {
		db.transaction(false, txn -> setContactAlias(txn, c, alias));
	}

	@Override
	public boolean contactExists(Transaction txn, AuthorId remoteAuthorId,
			AuthorId localAuthorId) throws DbException {
		return db.containsContact(txn, remoteAuthorId, localAuthorId);
	}

	@Override
	public boolean contactExists(AuthorId remoteAuthorId,
			AuthorId localAuthorId) throws DbException {
		return db.transactionWithResult(true, txn ->
				contactExists(txn, remoteAuthorId, localAuthorId));
	}

	@Override
	public void removeContact(Transaction txn, ContactId c)
			throws DbException {
		Contact contact = db.getContact(txn, c);
		for (ContactHook hook : hooks) hook.removingContact(txn, contact);
		db.removeContact(txn, c);
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof PendingContactStateChangedEvent) {
			PendingContactStateChangedEvent p =
					(PendingContactStateChangedEvent) e;
			states.put(p.getId(), p.getPendingContactState());
		}
	}
}
