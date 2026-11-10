package org.zerionproject.core.contact;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.ContactType;
import org.zerionproject.core.api.contact.OwnLinkChangedException;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.PendingContactState;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.OwnLinkException;
import org.zerionproject.core.api.contact.event.ContactConnectionKeysEvent;
import org.zerionproject.core.api.contact.event.PendingContactStateChangedEvent;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.HybridAgreementPrivateKey;
import org.zerionproject.core.api.crypto.HybridAgreementPublicKey;
import org.zerionproject.core.api.crypto.HybridCommitmentPublicKey;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.KeyParser;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
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
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.contact.request.ContactLinkStore;
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
import static org.zerionproject.core.api.identity.AuthorConstants.MAX_AUTHOR_NAME_LENGTH;
import static org.zerionproject.core.util.StringUtils.toUtf8;

@ThreadSafe
@NotNullByDefault
class ContactManagerImpl implements ContactManager, EventListener {

	static final String CONNECTION_KEYS_NAMESPACE =
			"org.zerionproject.transport.rootEvolution";
	static final String OUT_OF_SYNC_KEY_PREFIX = "outOfSync.";

	private final DatabaseComponent db;
	private final KeyManager keyManager;
	private final IdentityManager identityManager;
	private final PendingContactFactory pendingContactFactory;
	private final CryptoComponent crypto;
	private final PcsStateManager pcsStateManager;

	private final ContactLinkStore linkStore;
	private final Clock clock;
	private final PendingContactFlags flags;

	private final List<ContactHook> hooks = new CopyOnWriteArrayList<>();
	private final List<PendingContactHook> pendingHooks =
			new CopyOnWriteArrayList<>();
	private final Map<PendingContactId, PendingContactState> states =
			new ConcurrentHashMap<>();

	@Inject
	ContactManagerImpl(DatabaseComponent db,
			KeyManager keyManager,
			IdentityManager identityManager,
			PendingContactFactory pendingContactFactory,
			CryptoComponent crypto,
			PcsStateManager pcsStateManager,
			ContactLinkStore linkStore,
			Clock clock) {
		this.linkStore = linkStore;
		this.clock = clock;
		this.db = db;
		this.keyManager = keyManager;
		this.identityManager = identityManager;
		this.pendingContactFactory = pendingContactFactory;
		this.crypto = crypto;
		this.pcsStateManager = pcsStateManager;
		this.flags = new PendingContactFlags(db);
	}

	@Override
	public void registerContactHook(ContactHook hook) {
		hooks.add(hook);
	}

	@Override
	public void registerPendingContactHook(PendingContactHook hook) {
		pendingHooks.add(hook);
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
		ContactId c = db.addContact(txn, remote, local, null, verified, true,
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
		ContactId c = db.addContact(txn, remote, local, null, verified, true,
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
		return addContact(txn, p, remote, local, rootKey, timestamp, alice,
				verified, active, peerMlDsaSigPublicKey, null);
	}

	@Override
	public boolean intendsToReplaceKeys(Transaction txn, PendingContactId p)
			throws DbException {
		PendingContact pendingContact = db.getPendingContact(txn, p);
		return flags.peek(txn, pendingContact, PendingContactFlags.IN_PERSON)
				|| flags.peek(txn, pendingContact, PendingContactFlags.RE_ADD);
	}

	@Override
	public ContactId addContact(Transaction txn, PendingContactId p,
			Author remote, AuthorId local, SecretKey rootKey, long timestamp,
			boolean alice, boolean verified, boolean active,
			@Nullable byte[] peerMlDsaSigPublicKey,
			@Nullable Boolean peerReplacesKeys)
			throws DbException, GeneralSecurityException {
		requireNotReserved(remote);
		PendingContact pendingContact = db.getPendingContact(txn, p);
		boolean postQuantum = pendingContact.isPostQuantum();
		checkForSecurityDowngrade(txn, remote.getId(), postQuantum);
		byte[][] ourKeySnapshot = db.getPendingContactOurKeys(txn, p);
		boolean perAdd = usesCurrentPerAddKey(txn, ourKeySnapshot);
		boolean inPerson = flags.take(txn, pendingContact,
				PendingContactFlags.IN_PERSON);
		boolean reAdd = flags.take(txn, pendingContact,
				PendingContactFlags.RE_ADD);
		db.removePendingContact(txn, p);
		states.remove(p);
		if (db.containsContact(txn, remote.getId(), local)) {
			boolean replace = (inPerson || reAdd)
					&& !Boolean.FALSE.equals(peerReplacesKeys);
			if (!replace) throw new ContactExistsException(local, remote);
			ContactId existing = rekeyContact(txn, remote, local, rootKey,
					timestamp, alice, peerMlDsaSigPublicKey, inPerson, perAdd);
			for (PendingContactHook hook : pendingHooks) {
				hook.convertingPendingContact(txn, p, existing);
			}
			return existing;
		}
		verified = verified || inPerson;
		PublicKey theirPublicKey = pendingContact.getPublicKey();
		KeyPair ourKeyPair;
		if (theirPublicKey instanceof HybridCommitmentPublicKey) {
			KeyParser parser = crypto.getAgreementKeyParser();
			byte[] blob = theirPublicKey.getEncoded();
			theirPublicKey = parser.parsePublicKey(Arrays.copyOfRange(blob,
					HYBRID_COMMITMENT_BYTES,
					HYBRID_COMMITMENT_BYTES + HYBRID_RENDEZVOUS_X25519_BYTES));
			KeyPair hybrid = hybridKeyPairFor(txn, ourKeySnapshot);
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
		for (PendingContactHook hook : pendingHooks) {
			hook.convertingPendingContact(txn, p, c);
		}
		if (perAdd) identityManager.rotateHybridHandshakeKeys(txn);
		return c;
	}

	private boolean usesCurrentPerAddKey(Transaction txn,
			@Nullable byte[][] snapshot) throws DbException {
		if (snapshot == null) return true;
		KeyPair perAdd = identityManager.getHybridHandshakeKeys(txn);
		return perAdd != null && Arrays.equals(snapshot[0],
				perAdd.getPublic().getEncoded());
	}

	private ContactId rekeyContact(Transaction txn, Author remote,
			AuthorId local, SecretKey rootKey, long timestamp, boolean alice,
			@Nullable byte[] peerMlDsaSigPublicKey, boolean inPerson,
			boolean perAdd)
			throws DbException {
		Contact existing = getContact(txn, remote.getId(), local);
		ContactId c = existing.getId();
		keyManager.addRotationKeys(txn, c, rootKey, timestamp, alice, true);
		db.removePcsSessionState(txn, c,
				DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT);
		db.removePcsSessionState(txn, c,
				DatabaseComponent.PCS_SLOT_TRANSPORT_ROOT_PENDING);
		initializePcsState(txn, c, rootKey);
		if (existing.getMlDsaSigPublicKey() == null
				&& peerMlDsaSigPublicKey != null) {
			db.setContactMlDsaSigPublicKey(txn, c, peerMlDsaSigPublicKey);
		}
		if (inPerson && !existing.isVerified()) db.setContactVerified(txn, c);
		Settings s = new Settings();
		s.putBoolean(OUT_OF_SYNC_KEY_PREFIX + c.getInt(), false);
		s.putBoolean(org.zerionproject.transport.RootKeyStore
				.EVOLVES_KEY_PREFIX + c.getInt(), false);
		s.putBoolean(org.zerionproject.transport.RootKeyStore
				.ENFORCES_KEY_PREFIX + c.getInt(), false);
		s.putLong(org.zerionproject.transport.RootKeyStore
				.EVOLVED_AT_KEY_PREFIX + c.getInt(), 0);
		s.putInt(org.zerionproject.transport.RootKeyStore
				.STALLS_KEY_PREFIX + c.getInt(), 0);
		s.putLong(org.zerionproject.transport.RootKeyStore
				.STALLED_SINCE_KEY_PREFIX + c.getInt(), 0);
		s.putBoolean(org.zerionproject.transport.RootKeyStore
				.NOT_RENEWING_KEY_PREFIX + c.getInt(), false);
		db.mergeSettings(txn, s, CONNECTION_KEYS_NAMESPACE);
		txn.attach(new ContactConnectionKeysEvent(c, false));
		txn.attach(new org.zerionproject.core.api.contact.event
				.ContactKeyRenewalEvent(c, false));
		if (perAdd) identityManager.rotateHybridHandshakeKeys(txn);
		return c;
	}

	private KeyPair hybridKeyPairFor(Transaction txn,
			@Nullable byte[][] snapshot) throws DbException {
		if (snapshot != null) {
			return new KeyPair(new HybridAgreementPublicKey(snapshot[0]),
					new HybridAgreementPrivateKey(snapshot[1]));
		}
		KeyPair hybrid = identityManager.getHybridHandshakeKeys(txn);
		if (hybrid == null) throw new DbException();
		return hybrid;
	}

	@Override
	@Nullable
	public byte[][] getPendingContactOurKeys(Transaction txn,
			PendingContactId p) throws DbException {
		return db.getPendingContactOurKeys(txn, p);
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
		return addPendingContact(txn, link, alias, false);
	}

	private PendingContact addPendingContact(Transaction txn, String link,
			String alias, boolean reAddExisting)
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
		db.addPendingContact(txn, p, local, reAddExisting);
		if (reAddExisting) flags.put(txn, p, PendingContactFlags.RE_ADD);
		if (p.isPostQuantum()) {
			KeyPair hybridSnapshot = identityManager.getHybridHandshakeKeys(txn);
			if (hybridSnapshot != null) {
				db.setPendingContactOurKeys(txn, p.getId(),
						hybridSnapshot.getPublic().getEncoded(),
						hybridSnapshot.getPrivate().getEncoded());
			}
		}
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
	public PendingContact addPendingContact(String link, String alias,
			String sharedOwnLink)
			throws DbException, FormatException, GeneralSecurityException {
		return addPendingContact(link, alias, sharedOwnLink, false, false);
	}

	@Override
	public PendingContact addPendingContact(String link, String alias,
			@Nullable String sharedOwnLink, boolean reAddExisting,
			boolean verifiedInPerson)
			throws DbException, FormatException, GeneralSecurityException {
		Transaction txn = db.startTransaction(false);
		try {
			if (sharedOwnLink != null
					&& !sharedOwnLink.equals(getHandshakeLink(txn))) {
				throw new OwnLinkChangedException();
			}
			PendingContact p = addPendingContact(txn, link, alias,
					reAddExisting);
			if (verifiedInPerson) markScannedInPerson(txn, p);
			db.commitTransaction(txn);
			return p;
		} finally {
			db.endTransaction(txn);
		}
	}

	@Override
	public PendingContact addPendingContactBoundToSharedLink(String link,
			String alias, boolean reAddExisting, boolean verifiedInPerson)
			throws DbException, FormatException, GeneralSecurityException {
		return addPendingContactBoundToSharedLink(link, alias, null,
				reAddExisting, verifiedInPerson);
	}

	@Override
	public PendingContact addPendingContactBoundToSharedLink(String link,
			String alias, @Nullable String shownOwnLink,
			boolean reAddExisting, boolean verifiedInPerson)
			throws DbException, FormatException, GeneralSecurityException {
		boolean changed = false;
		PendingContact p = null;
		Transaction txn = db.startTransaction(false);
		try {
			ContactLink binding = linkStore.freshPerAddBinding(txn,
					clock.currentTimeMillis());
			if (binding != null && !Arrays.equals(binding.getLinkId(),
					linkStore.currentPerAddCommitment(txn))) {
				changed = true;
			} else if (binding == null && shownOwnLink != null
					&& !sameLinkBody(shownOwnLink, getHandshakeLink(txn))) {
				changed = true;
			} else {
				p = addPendingContact(txn, link, alias, reAddExisting);
				if (verifiedInPerson) markScannedInPerson(txn, p);
			}
			linkStore.forgetPerAddBinding(txn);
			db.commitTransaction(txn);
		} finally {
			db.endTransaction(txn);
		}
		if (changed || p == null) throw new OwnLinkChangedException();
		return p;
	}

	@Override
	public PendingContact addPendingContactFromInvite(String link,
			String alias, @Nullable String sharedZeroId)
			throws DbException, FormatException, GeneralSecurityException {
		return addPendingContactFromInvite(link, alias, sharedZeroId, false);
	}

	@Override
	public PendingContact addPendingContactFromInvite(String link,
			String alias, @Nullable String sharedZeroId,
			boolean reAddExisting)
			throws DbException, FormatException, GeneralSecurityException {
		boolean changed = false;
		PendingContact p = null;
		Transaction txn = db.startTransaction(false);
		try {
			if (linkStore.isOwnLink(txn, linkStore.getCodec().parse(link))) {
				throw new OwnLinkException();
			}
			ContactLink binding = sharedZeroId != null ? null
					: linkStore.freshPerAddBinding(txn,
					clock.currentTimeMillis());
			ContactLink current = linkStore.getZeroId(txn);
			if (binding != null && current != null
					&& current.getLastSharedAt() >= binding.getLastSharedAt()) {
				binding = null;
			}
			if (binding != null) {
				if (!Arrays.equals(binding.getLinkId(),
						linkStore.currentPerAddCommitment(txn))) {
					changed = true;
				} else {
					p = addPendingContact(txn, link, alias, reAddExisting);
				}
				linkStore.forgetPerAddBinding(txn);
			} else {
				ContactLink zeroId = linkStore.getOrCreateZeroId(txn);
				if (sharedZeroId != null && !sameLinkBody(sharedZeroId,
						linkStore.linkString(zeroId))) {
					changed = true;
				} else {
					p = addPendingContactWithKeys(txn, link, alias,
							linkStore.keyPair(zeroId), reAddExisting);
				}
			}
			db.commitTransaction(txn);
		} finally {
			db.endTransaction(txn);
		}
		if (changed || p == null) throw new OwnLinkChangedException();
		return p;
	}

	private boolean sameLinkBody(String a, String b) {
		try {
			return Arrays.equals(linkStore.getCodec().parse(a).getBody(),
					linkStore.getCodec().parse(b).getBody());
		} catch (FormatException e) {
			return false;
		}
	}

	private PendingContact addPendingContactWithKeys(Transaction txn,
			String link, String alias, KeyPair ourHybridKeys,
			boolean reAddExisting)
			throws DbException, FormatException, GeneralSecurityException {
		PendingContact p =
				pendingContactFactory.createPendingContact(link, alias);
		byte[] newKey = p.getPublicKey().getEncoded();
		for (PendingContact existing : db.getPendingContacts(txn)) {
			if (Arrays.equals(existing.getPublicKey().getEncoded(), newKey)) {
				return existing;
			}
		}
		AuthorId local = identityManager.getLocalAuthor(txn).getId();
		db.addPendingContact(txn, p, local, reAddExisting);
		if (reAddExisting) flags.put(txn, p, PendingContactFlags.RE_ADD);
		if (p.isPostQuantum()) {
			db.setPendingContactOurKeys(txn, p.getId(),
					ourHybridKeys.getPublic().getEncoded(),
					ourHybridKeys.getPrivate().getEncoded());
		} else {
			KeyPair ourKeyPair = identityManager.getHandshakeKeys(txn);
			keyManager.addPendingContact(txn, p.getId(), p.getPublicKey(),
					ourKeyPair);
		}
		return p;
	}

	private void markScannedInPerson(Transaction txn, PendingContact p)
			throws DbException {
		flags.put(txn, p, PendingContactFlags.IN_PERSON);
	}

	@Override
	public void recordPerAddLinkShared(String link)
			throws DbException, FormatException {
		Transaction txn = db.startTransaction(false);
		try {
			recordPerAddLinkShared(txn, link, clock.currentTimeMillis());
			db.commitTransaction(txn);
		} finally {
			db.endTransaction(txn);
		}
	}

	@Override
	public void recordPerAddLinkShared(Transaction txn, String link,
			long sharedAt) throws DbException, FormatException {
		linkStore.recordPerAddShare(txn, link, sharedAt);
	}

	@Override
	public boolean isConnectionOutOfSync(ContactId c) throws DbException {
		return db.transactionWithResult(true, txn ->
				isConnectionOutOfSync(txn, c));
	}

	@Override
	public boolean isConnectionOutOfSync(Transaction txn, ContactId c)
			throws DbException {
		return db.getSettings(txn, CONNECTION_KEYS_NAMESPACE)
				.getBoolean(OUT_OF_SYNC_KEY_PREFIX + c.getInt(), false);
	}

	@Override
	public boolean isConnectionRenewalStalled(ContactId c)
			throws DbException {
		return db.transactionWithResult(true, txn ->
				db.getSettings(txn, CONNECTION_KEYS_NAMESPACE).getBoolean(
						org.zerionproject.transport.RootKeyStore
								.NOT_RENEWING_KEY_PREFIX + c.getInt(), false));
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
		flags.clear(txn, db.getPendingContact(txn, p));
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
	public void setContactVerified(ContactId c) throws DbException {
		db.transaction(false, txn -> db.setContactVerified(txn, c));
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
		Settings forgotten = new Settings();
		forgotten.putBoolean(OUT_OF_SYNC_KEY_PREFIX + c.getInt(), false);
		forgotten.putBoolean(org.zerionproject.transport.RootKeyStore
				.EVOLVES_KEY_PREFIX + c.getInt(), false);
		forgotten.putBoolean(org.zerionproject.transport.RootKeyStore
				.ENFORCES_KEY_PREFIX + c.getInt(), false);
		forgotten.putLong(org.zerionproject.transport.RootKeyStore
				.EVOLVED_AT_KEY_PREFIX + c.getInt(), 0);
		forgotten.putInt(org.zerionproject.transport.RootKeyStore
				.STALLS_KEY_PREFIX + c.getInt(), 0);
		forgotten.putLong(org.zerionproject.transport.RootKeyStore
				.STALLED_SINCE_KEY_PREFIX + c.getInt(), 0);
		forgotten.putBoolean(org.zerionproject.transport.RootKeyStore
				.NOT_RENEWING_KEY_PREFIX + c.getInt(), false);
		db.mergeSettings(txn, forgotten, CONNECTION_KEYS_NAMESPACE);
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
		pcsStateManager.initializePairingRoot(txn, contactId, rootKey);
	}

	@Override
	public SecretKey deriveContactKey(ContactId c, String label,
			byte[]... inputs) throws DbException {
		return db.transactionWithResult(true, txn ->
				deriveContactKey(txn, c, label, inputs));
	}

	@Override
	public SecretKey deriveContactKey(Transaction txn, ContactId c,
			String label, byte[]... inputs) throws DbException {
		PcsSessionState state = pcsStateManager.loadSendState(txn, c);
		SecretKey rootKey = state == null ? null : state.getRootKey();
		if (rootKey == null) throw new NoSuchContactException();
		try {
			return crypto.deriveKey(label, rootKey, inputs);
		} finally {
			rootKey.clear();
			state.getChainKey().clear();
		}
	}
}
