package org.zerionproject.core.transport.agreement;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.BdfIncomingMessageHook;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager.ContactHook;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.zerionproject.core.api.plugin.PluginConfig;
import org.zerionproject.core.api.plugin.PluginFactory;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Group.Visibility;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.api.transport.KeySetId;
import org.zerionproject.core.api.transport.agreement.TransportKeyAgreementManager;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.api.versioning.ClientVersioningManager.ClientVersioningHook;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static java.lang.Math.min;
import static java.util.Collections.singletonMap;
import static org.zerionproject.core.api.Bytes.compare;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.DEFER;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.REJECT;
import static org.zerionproject.core.transport.agreement.MessageType.ACTIVATE;
import static org.zerionproject.core.transport.agreement.MessageType.KEY;
import static org.zerionproject.core.transport.agreement.State.ACTIVATED;
import static org.zerionproject.core.transport.agreement.State.AWAIT_ACTIVATE;
import static org.zerionproject.core.transport.agreement.State.AWAIT_KEY;
import static org.zerionproject.core.transport.agreement.TransportKeyAgreementConstants.MSG_KEY_MESSAGE_TYPE;
import static org.zerionproject.core.transport.agreement.TransportKeyAgreementConstants.MSG_KEY_PUBLIC_KEY;
import static org.zerionproject.core.transport.agreement.TransportKeyAgreementConstants.MSG_KEY_TRANSPORT_ID;
import static org.briarproject.nullsafety.NullSafety.requireNonNull;

@Immutable
@NotNullByDefault
class TransportKeyAgreementManagerImpl extends BdfIncomingMessageHook
		implements TransportKeyAgreementManager, OpenDatabaseHook, ContactHook,
		ClientVersioningHook {
	private final ContactGroupFactory contactGroupFactory;
	private final ClientVersioningManager clientVersioningManager;
	private final IdentityManager identityManager;
	private final KeyManager keyManager;
	private final MessageEncoder messageEncoder;
	private final SessionEncoder sessionEncoder;
	private final SessionParser sessionParser;
	private final TransportKeyAgreementCrypto crypto;

	private final List<TransportId> transports;
	private final Group localGroup;

	@Inject
	TransportKeyAgreementManagerImpl(
			DatabaseComponent db,
			ClientHelper clientHelper,
			MetadataParser metadataParser,
			ContactGroupFactory contactGroupFactory,
			ClientVersioningManager clientVersioningManager,
			IdentityManager identityManager,
			KeyManager keyManager,
			MessageEncoder messageEncoder,
			SessionEncoder sessionEncoder,
			SessionParser sessionParser,
			TransportKeyAgreementCrypto crypto,
			PluginConfig config) {
		super(db, clientHelper, metadataParser);
		this.contactGroupFactory = contactGroupFactory;
		this.clientVersioningManager = clientVersioningManager;
		this.identityManager = identityManager;
		this.keyManager = keyManager;
		this.messageEncoder = messageEncoder;
		this.sessionEncoder = sessionEncoder;
		this.sessionParser = sessionParser;
		this.crypto = crypto;
		transports = new ArrayList<>();
		for (PluginFactory<?> f : config.getDuplexFactories()) {
			transports.add(f.getId());
		}
		for (PluginFactory<?> f : config.getSimplexFactories()) {
			transports.add(f.getId());
		}
		localGroup = contactGroupFactory.createLocalGroup(CLIENT_ID,
				MAJOR_VERSION);
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		Collection<Contact> contacts = db.getContacts(txn);
		if (!db.containsGroup(txn, localGroup.getId())) {
			db.addGroup(txn, localGroup);
			for (Contact c : contacts) addingContact(txn, c);
		}
		Map<ContactId, Collection<TransportId>> transportsWithKeys =
				db.getTransportsWithKeys(txn);
		for (Contact c : contacts) {
			Collection<TransportId> withKeys =
					transportsWithKeys.get(c.getId());
			for (TransportId t : transports) {
				if (withKeys == null || !withKeys.contains(t)) {
					GroupId contactGroupId = getContactGroup(c).getId();
					SavedSession ss = loadSession(txn, contactGroupId, t);
					if (ss == null) {
						startSession(txn, contactGroupId, t);
					}
				}
			}
		}
	}

	@Override
	public void addingContact(Transaction txn, Contact c) throws DbException {
		Group g = getContactGroup(c);
		db.addGroup(txn, g);
		clientHelper.setContactId(txn, g.getId(), c.getId());
		Visibility client = clientVersioningManager.getClientVisibility(txn,
				c.getId(), CLIENT_ID, MAJOR_VERSION);
		db.setGroupVisibility(txn, c.getId(), g.getId(), client);
	}

	@Override
	public void removingContact(Transaction txn, Contact c) throws DbException {
		db.removeGroup(txn, getContactGroup(c));
	}

	@Override
	public void onClientVisibilityChanging(Transaction txn, Contact c,
			Visibility v) throws DbException {
		Group g = getContactGroup(c);
		db.setGroupVisibility(txn, c.getId(), g.getId(), v);
	}

	@Override
	protected DeliveryAction incomingMessage(Transaction txn, Message m,
			BdfList body, BdfDictionary meta)
			throws DbException, FormatException {
		MessageType type =
				MessageType.fromValue(meta.getInt(MSG_KEY_MESSAGE_TYPE));
		TransportId t = new TransportId(meta.getString(MSG_KEY_TRANSPORT_ID));
		if (!transports.contains(t)) {
			return DEFER;
		}
		SavedSession ss = loadSession(txn, m.getGroupId(), t);
		if (type == KEY) return handleKeyMessage(txn, t, m, meta, ss);
		else if (type == ACTIVATE) return handleActivateMessage(txn, t, ss);
		else throw new AssertionError();
	}

	private DeliveryAction handleKeyMessage(Transaction txn, TransportId t,
			Message m, BdfDictionary meta, @Nullable SavedSession ss)
			throws DbException, FormatException {
		ContactId c = clientHelper.getContactId(txn, m.getGroupId());
		boolean haveKeys = db.containsTransportKeys(txn, c, t);
		if (ss == null) {
			if (haveKeys) {
				return handleKeyMessageForNewSession(txn, c, t, m, meta);
			} else {
				throw new IllegalStateException();
			}
		} else if (ss.session.getState() == AWAIT_KEY) {
			if (haveKeys) {
				throw new IllegalStateException();
			} else {
				return handleKeyMessageForExistingSession(txn, c, t, m, meta,
						ss);
			}
		} else {
			return REJECT;
		}
	}

	private DeliveryAction handleActivateMessage(Transaction txn,
			TransportId t, @Nullable SavedSession ss) throws DbException {
		if (ss != null && ss.session.getState() == AWAIT_ACTIVATE) {
			KeySetId keySetId = requireNonNull(ss.session.getKeySetId());
			keyManager.activateKeys(txn, singletonMap(t, keySetId));
			Session session = new Session(ACTIVATED,
					ss.session.getLastLocalMessageId(), null, null, null);
			saveSession(txn, t, ss.storageId, session);
			return ACCEPT_DO_NOT_SHARE;
		} else {
			return REJECT;
		}
	}

	private DeliveryAction handleKeyMessageForNewSession(Transaction txn,
			ContactId c, TransportId t, Message m, BdfDictionary meta)
			throws DbException, FormatException {
		KeyPair localKeyPair = crypto.generateKeyPair();
		PublicKey remotePublicKey =
				crypto.parsePublicKey(meta.getRaw(MSG_KEY_PUBLIC_KEY));
		Message keyMessage = sendKeyMessage(txn, m.getGroupId(), t,
				localKeyPair.getPublic());
		long minTimestamp = min(keyMessage.getTimestamp(), m.getTimestamp());
		SecretKey rootKey;
		try {
			rootKey = crypto.deriveRootKey(localKeyPair, remotePublicKey);
		} catch (GeneralSecurityException e) {
			return REJECT;
		}
		boolean alice = isLocalPartyAlice(txn, db.getContact(txn, c));
		KeySetId keySetId = keyManager.addRotationKeys(txn, c, t, rootKey,
				minTimestamp, alice, false);
		Message activateMessage =
				sendActivateMessage(txn, m.getGroupId(), t, keyMessage.getId());
		Session session = new Session(AWAIT_ACTIVATE, activateMessage.getId(),
				null, null, keySetId);
		saveNewSession(txn, m.getGroupId(), t, session);
		return ACCEPT_DO_NOT_SHARE;
	}

	private DeliveryAction handleKeyMessageForExistingSession(Transaction txn,
			ContactId c, TransportId t, Message m, BdfDictionary meta,
			SavedSession ss) throws DbException, FormatException {
		KeyPair localKeyPair = requireNonNull(ss.session.getLocalKeyPair());
		PublicKey remotePublicKey =
				crypto.parsePublicKey(meta.getRaw(MSG_KEY_PUBLIC_KEY));
		long localTimestamp = requireNonNull(ss.session.getLocalTimestamp());
		long minTimestamp = min(localTimestamp, m.getTimestamp());
		SecretKey rootKey;
		try {
			rootKey = crypto.deriveRootKey(localKeyPair, remotePublicKey);
		} catch (GeneralSecurityException e) {
			return REJECT;
		}
		boolean alice = isLocalPartyAlice(txn, db.getContact(txn, c));
		KeySetId keySetId = keyManager.addRotationKeys(txn, c, t, rootKey,
				minTimestamp, alice, false);
		MessageId previousMessageId =
				requireNonNull(ss.session.getLastLocalMessageId());
		Message activateMessage =
				sendActivateMessage(txn, m.getGroupId(), t, previousMessageId);
		Session session = new Session(AWAIT_ACTIVATE, activateMessage.getId(),
				null, null, keySetId);
		saveSession(txn, t, ss.storageId, session);
		return ACCEPT_DO_NOT_SHARE;
	}

	private void startSession(Transaction txn, GroupId contactGroupId,
			TransportId t) throws DbException {
		KeyPair localKeyPair = crypto.generateKeyPair();
		Message keyMessage = sendKeyMessage(txn, contactGroupId, t,
				localKeyPair.getPublic());
		Session session = new Session(AWAIT_KEY, keyMessage.getId(),
				localKeyPair, keyMessage.getTimestamp(), null);
		saveNewSession(txn, contactGroupId, t, session);
	}

	@Nullable
	private SavedSession loadSession(Transaction txn, GroupId contactGroupId,
			TransportId t) throws DbException {
		try {
			BdfDictionary query = sessionEncoder.getSessionQuery(t);
			Collection<MessageId> ids =
					clientHelper.getMessageIds(txn, contactGroupId, query);
			if (ids.size() > 1) throw new DbException();
			if (ids.isEmpty()) {
				return null;
			}
			MessageId storageId = ids.iterator().next();
			BdfDictionary bdfSession =
					clientHelper.getMessageMetadataAsDictionary(txn, storageId);
			Session session = sessionParser.parseSession(bdfSession);
			return new SavedSession(session, storageId);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void saveNewSession(Transaction txn, GroupId contactGroupId,
			TransportId t, Session session) throws DbException {
		Message m =
				clientHelper.createMessageForStoringMetadata(contactGroupId);
		db.addLocalMessage(txn, m, new Metadata(), false, false);
		MessageId storageId = m.getId();
		saveSession(txn, t, storageId, session);
	}

	private void saveSession(Transaction txn, TransportId t,
			MessageId storageId, Session session) throws DbException {
		BdfDictionary meta = sessionEncoder.encodeSession(session, t);
		try {
			clientHelper.mergeMessageMetadata(txn, storageId, meta);
		} catch (FormatException e) {
			throw new AssertionError();
		}
	}

	private Message sendKeyMessage(Transaction txn, GroupId contactGroupId,
			TransportId t, PublicKey publicKey) throws DbException {
		Message m = messageEncoder.encodeKeyMessage(contactGroupId, t,
				publicKey);
		sendMessage(txn, t, m, KEY);
		return m;
	}

	private Message sendActivateMessage(Transaction txn,
			GroupId contactGroupId, TransportId t, MessageId previousMessageId)
			throws DbException {
		Message m = messageEncoder.encodeActivateMessage(contactGroupId, t,
				previousMessageId);
		sendMessage(txn, t, m, ACTIVATE);
		return m;
	}

	private void sendMessage(Transaction txn, TransportId t, Message m,
			MessageType type) throws DbException {
		BdfDictionary meta =
				messageEncoder.encodeMessageMetadata(t, type, true);
		try {
			clientHelper.addLocalMessage(txn, m, meta, true, false);
		} catch (FormatException e) {
			throw new AssertionError();
		}
	}

	private Group getContactGroup(Contact c) {
		return contactGroupFactory.createContactGroup(CLIENT_ID,
				MAJOR_VERSION, c);
	}

	private boolean isLocalPartyAlice(Transaction txn, Contact c)
			throws DbException {
		Author local = identityManager.getLocalAuthor(txn);
		Author remote = c.getAuthor();
		return compare(local.getId().getBytes(), remote.getId().getBytes()) < 0;
	}

	private static class SavedSession {

		private final Session session;
		private final MessageId storageId;

		private SavedSession(Session session, MessageId storageId) {
			this.session = session;
			this.storageId = storageId;
		}
	}
}
