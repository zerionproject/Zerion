package org.zerionproject.core.contact;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.ContactType;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.PendingContactState;
import org.zerionproject.core.api.contact.event.PendingContactStateChangedEvent;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridCommitmentPublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.DhRatchetState;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.Mode3FullState;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.crypto.pcs.PqRatchetState;
import org.zerionproject.core.crypto.pcs.PcsStateManager;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.ContactExistsException;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchContactException;
import org.zerionproject.core.api.db.SecurityDowngradeException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.ReservedNames;
import org.zerionproject.core.api.transport.KeyManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;

import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_COMMITMENT_BYTES;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.HYBRID_RENDEZVOUS_X25519_BYTES;
import static org.zerionproject.core.api.contact.PendingContactState.WAITING_FOR_CONNECTION;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MODE3_FULL_ENABLED;
import static org.zerionproject.core.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.zerionproject.core.util.StringUtils.toUtf8;

@ThreadSafe
@NotNullByDefault
class ContactManagerImpl implements ContactManager, EventListener {

	private final DatabaseComponent db;
	private final KeyManager keyManager;
	private final IdentityManager identityManager;
	private final PendingContactFactory pendingContactFactory;
	private final CryptoComponent crypto;
	private final PcsStateManager pcsStateManager;
	private final Mode3FullRatchet mode3FullRatchet;

	private final List<ContactHook> hooks = new CopyOnWriteArrayList<>();
	private final Map<PendingContactId, PendingContactState> states =
			new ConcurrentHashMap<>();

	@Inject
	ContactManagerImpl(DatabaseComponent db,
			KeyManager keyManager,
			IdentityManager identityManager,
			PendingContactFactory pendingContactFactory,
			CryptoComponent crypto,
			PcsStateManager pcsStateManager,
			Mode3FullRatchet mode3FullRatchet) {
		this.db = db;
		this.keyManager = keyManager;
		this.identityManager = identityManager;
		this.pendingContactFactory = pendingContactFactory;
		this.crypto = crypto;
		this.pcsStateManager = pcsStateManager;
		this.mode3FullRatchet = mode3FullRatchet;
	}

	@Override
	public void registerContactHook(ContactHook hook) {
		hooks.add(hook);
	}

	@Override
	public ContactId addContact(Transaction txn, Author remote, AuthorId local,
			SecretKey rootKey, long timestamp, boolean alice, boolean verified,
			boolean active) throws DbException {
		return addContact(txn, remote, local, rootKey, timestamp, alice,
				verified, active, (byte[]) null);
	}

	@Override
	public ContactId addContact(Transaction txn, Author remote, AuthorId local,
			SecretKey rootKey, long timestamp, boolean alice, boolean verified,
			boolean active,
			@Nullable byte[] peerMlDsaSigPublicKey) throws DbException {
		requireNotReserved(remote);
		ContactId c = db.addContact(txn, remote, local, null, verified, false,
				false, peerMlDsaSigPublicKey);
		keyManager.addRotationKeys(txn, c, rootKey, timestamp, alice, active);
		initializePcsState(txn, c, rootKey);
		Contact contact = db.getContact(txn, c);
		for (ContactHook hook : hooks) hook.addingContact(txn, contact);
		return c;
	}

	@Override
	public ContactId addContact(Transaction txn, Author remote, AuthorId local,
			SecretKey rootKey, boolean verified,
			@Nullable byte[] peerMlDsaSigPublicKey) throws DbException {
		requireNotReserved(remote);
		ContactId c = db.addContact(txn, remote, local, null, verified, false,
				false, peerMlDsaSigPublicKey);
		initializePcsState(txn, c, rootKey);
		Contact contact = db.getContact(txn, c);
		for (ContactHook hook : hooks) hook.addingContact(txn, contact);
		return c;
	}

	@Override
	public ContactId addContact(Transaction txn, PendingContactId p,
			Author remote, AuthorId local, SecretKey rootKey, long timestamp,
			boolean alice, boolean verified, boolean active)
			throws DbException, GeneralSecurityException {
		return addContact(txn, p, remote, local, rootKey, timestamp, alice,
				verified, active, (byte[]) null);
	}

	@Override
	public ContactId addContact(Transaction txn, PendingContactId p,
			Author remote, AuthorId local, SecretKey rootKey, long timestamp,
			boolean alice, boolean verified, boolean active,
			@Nullable byte[] peerMlDsaSigPublicKey)
			throws DbException, GeneralSecurityException {
		requireNotReserved(remote);
		if (db.containsContact(txn, remote.getId(), local)) {
			throw new ContactExistsException(local, remote);
		}
		PendingContact pendingContact = db.getPendingContact(txn, p);
		boolean postQuantum = pendingContact.isPostQuantum();
		checkForSecurityDowngrade(txn, remote.getId(), postQuantum);
		db.removePendingContact(txn, p);
		states.remove(p);
		PublicKey theirPublicKey = pendingContact.getPublicKey();
		KeyPair ourKeyPair;
		if (theirPublicKey instanceof HybridCommitmentPublicKey) {
			KeyParser parser = crypto.getAgreementKeyParser();
			byte[] blob = theirPublicKey.getEncoded();
			theirPublicKey = parser.parsePublicKey(Arrays.copyOfRange(blob,
					HYBRID_COMMITMENT_BYTES,
					HYBRID_COMMITMENT_BYTES + HYBRID_RENDEZVOUS_X25519_BYTES));
			KeyPair hybrid = identityManager.getHybridHandshakeKeys(txn);
			if (hybrid == null) throw new DbException();
			byte[] pub = hybrid.getPublic().getEncoded();
			byte[] priv = hybrid.getPrivate().getEncoded();
			ourKeyPair = new KeyPair(
					parser.parsePublicKey(Arrays.copyOfRange(pub, 0,
							HYBRID_RENDEZVOUS_X25519_BYTES)),
					parser.parsePrivateKey(Arrays.copyOfRange(priv, 0,
							HYBRID_RENDEZVOUS_X25519_BYTES)));
		} else {
			ourKeyPair = identityManager.getHandshakeKeys(txn);
		}
		ContactId c = db.addContact(txn, remote, local, theirPublicKey,
				verified, postQuantum, false,
				peerMlDsaSigPublicKey);
		String alias = pendingContact.getAlias();
		if (!alias.equals(remote.getName())) db.setContactAlias(txn, c, alias);
		keyManager.addContact(txn, c, theirPublicKey, ourKeyPair);
		keyManager.addRotationKeys(txn, c, rootKey, timestamp, alice, active);
		initializePcsState(txn, c, rootKey);
		Contact contact = db.getContact(txn, c);
		for (ContactHook hook : hooks) hook.addingContact(txn, contact);
		return c;
	}

	private void requireNotReserved(Author remote) throws DbException {
		if (ReservedNames.isReserved(remote.getName())) {
			throw new DbException();
		}
	}

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
		requireNotReserved(remote);
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
			KeyPair hybridKeyPair = identityManager.getHybridHandshakeKeys(txn);
			if (hybridKeyPair != null) {
				return pendingContactFactory.createHandshakeLink(
						hybridKeyPair.getPublic());
			}
		}
		KeyPair keyPair = identityManager.getHandshakeKeys(txn);
		return pendingContactFactory.createHandshakeLink(keyPair.getPublic());
	}

	@Override
	public PendingContact addPendingContact(Transaction txn, String link,
			String alias)
			throws DbException, FormatException, GeneralSecurityException {
		PendingContact p =
				pendingContactFactory.createPendingContact(link, alias);

		byte[] newKey = p.getPublicKey().getEncoded();
		for (PendingContact existing : db.getPendingContacts(txn)) {
			if (java.util.Arrays.equals(
					existing.getPublicKey().getEncoded(), newKey)) {
				return existing;
			}
		}
		AuthorId local = identityManager.getLocalAuthor(txn).getId();
		db.addPendingContact(txn, p, local);
		if (p.isClassical()) {
			KeyPair ourKeyPair = identityManager.getHandshakeKeys(txn);
			keyManager.addPendingContact(txn, p.getId(), p.getPublicKey(),
					ourKeyPair);
		}
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
			if (ReservedNames.isReserved(alias))
				throw new IllegalArgumentException("reserved name");
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

	private void initializePcsState(Transaction txn, ContactId contactId,
			SecretKey rootKey) throws DbException {
		KeyPair dhKeyPair = crypto.generateAgreementKeyPair();
		DhRatchetState dhState = new DhRatchetState(dhKeyPair, null);
		PcsSessionState sendState;
		PcsSessionState receiveState;
		if (MODE3_FULL_ENABLED) {
			Mode3FullState sharedMode3Full =
					mode3FullRatchet.createInitialState();
			sendState = PcsSessionState.createInitialMode3Full(
					rootKey, rootKey, dhState, sharedMode3Full);
			receiveState = PcsSessionState.createInitialMode3Full(
					rootKey, rootKey, dhState, sharedMode3Full);
		} else {
			sendState = PcsSessionState.createInitialMode3(
					rootKey, rootKey, dhState);
			receiveState = PcsSessionState.createInitialMode3(
					rootKey, rootKey, dhState);
		}
		pcsStateManager.initializeMode2State(txn, contactId, sendState,
				receiveState);
		PqRatchetState pqState = PqRatchetState.createReady(
				System.currentTimeMillis());
		pcsStateManager.savePqState(txn, contactId, pqState);
	}
}
