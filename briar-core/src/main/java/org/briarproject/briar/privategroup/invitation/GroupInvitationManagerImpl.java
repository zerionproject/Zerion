package org.briarproject.briar.privategroup.invitation;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.cleanup.CleanupHook;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager.ContactHook;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.HybridSignaturePublicKey;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.identity.Author;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.Group.Visibility;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.MessageStatus;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.bramble.api.versioning.ClientVersioningManager.ClientVersioningHook;
import org.briarproject.briar.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.client.ProtocolStateException;
import org.briarproject.briar.api.client.SessionId;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.conversation.DeletionResult;
import org.briarproject.briar.api.privategroup.PrivateGroup;
import org.briarproject.briar.api.privategroup.PrivateGroupFactory;
import org.briarproject.briar.api.privategroup.PrivateGroupManager;
import org.briarproject.briar.api.privategroup.PrivateGroupManager.PrivateGroupHook;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationItem;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationRequest;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationResponse;
import org.briarproject.briar.api.sharing.SharingManager.SharingStatus;
import org.briarproject.briar.client.ConversationClientImpl;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.bramble.api.sync.Group.Visibility.SHARED;
import static org.briarproject.bramble.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.briarproject.briar.privategroup.invitation.CreatorState.DISSOLVED;
import static org.briarproject.briar.privategroup.invitation.CreatorState.ERROR;
import static org.briarproject.briar.privategroup.invitation.CreatorState.INVITED;
import static org.briarproject.briar.privategroup.invitation.CreatorState.JOINED;
import static org.briarproject.briar.privategroup.invitation.CreatorState.START;
import static org.briarproject.briar.privategroup.invitation.MessageType.ABORT;
import static org.briarproject.briar.privategroup.invitation.MessageType.INVITE;
import static org.briarproject.briar.privategroup.invitation.MessageType.JOIN;
import static org.briarproject.briar.privategroup.invitation.MessageType.LEAVE;
import static org.briarproject.briar.privategroup.invitation.Role.CREATOR;
import static org.briarproject.briar.privategroup.invitation.Role.INVITEE;
import static org.briarproject.briar.privategroup.invitation.Role.PEER;

@Immutable
@NotNullByDefault
class GroupInvitationManagerImpl extends ConversationClientImpl
		implements GroupInvitationManager, OpenDatabaseHook, ContactHook,
		PrivateGroupHook, ClientVersioningHook, CleanupHook {

	private final ClientVersioningManager clientVersioningManager;
	private final ContactGroupFactory contactGroupFactory;
	private final PrivateGroupFactory privateGroupFactory;
	private final PrivateGroupManager privateGroupManager;
	private final MessageParser messageParser;
	private final SessionParser sessionParser;
	private final SessionEncoder sessionEncoder;
	private final ProtocolEngine<CreatorSession> creatorEngine;
	private final ProtocolEngine<InviteeSession> inviteeEngine;
	private final ProtocolEngine<PeerSession> peerEngine;
	private final CryptoComponent crypto;
	private final org.briarproject.bramble.api.identity.IdentityManager
			identityManager;

	@Inject
	GroupInvitationManagerImpl(DatabaseComponent db,
			ClientHelper clientHelper,
			ClientVersioningManager clientVersioningManager,
			MetadataParser metadataParser, MessageTracker messageTracker,
			ContactGroupFactory contactGroupFactory,
			PrivateGroupFactory privateGroupFactory,
			PrivateGroupManager privateGroupManager,
			MessageParser messageParser, SessionParser sessionParser,
			SessionEncoder sessionEncoder,
			ProtocolEngineFactory engineFactory,
			CryptoComponent crypto,
			org.briarproject.bramble.api.identity.IdentityManager
					identityManager) {
		super(db, clientHelper, metadataParser, messageTracker);
		this.clientVersioningManager = clientVersioningManager;
		this.contactGroupFactory = contactGroupFactory;
		this.privateGroupFactory = privateGroupFactory;
		this.privateGroupManager = privateGroupManager;
		this.messageParser = messageParser;
		this.sessionParser = sessionParser;
		this.sessionEncoder = sessionEncoder;
		this.crypto = crypto;
		this.identityManager = identityManager;
		creatorEngine = engineFactory.createCreatorEngine();
		inviteeEngine = engineFactory.createInviteeEngine();
		peerEngine = engineFactory.createPeerEngine();
	}

	private byte[] lookupAuthorMlDsaPub(Transaction txn, byte[] ed25519PubKey)
			throws DbException {
		org.briarproject.bramble.api.identity.LocalAuthor la =
				identityManager.getLocalAuthor(txn);
		if (java.util.Arrays.equals(la.getPublicKey().getEncoded(),
				ed25519PubKey)) {
			return identityManager.getLocalMlDsaSigPublicKey(txn);
		}
		for (Contact c : db.getContacts(txn)) {
			byte[] p = c.getAuthor().getPublicKey().getEncoded();
			if (java.util.Arrays.equals(p, ed25519PubKey)) {
				return c.getMlDsaSigPublicKey();
			}
		}
		return null;
	}

	private static final java.util.logging.Logger DIAG =
			java.util.logging.Logger.getLogger("ZN-DIAG-INVITE-LOCK");

	private void enforceInviteHybridLock(Transaction txn, Message m,
			BdfList body) throws DbException, FormatException {
		int type = body.getInt(0);
		DIAG.info("enforceInviteHybridLock entry type=" + type);
		if (type != INVITE.getValue()) return;
		BdfList creatorList = body.getList(1);
		org.briarproject.bramble.api.identity.Author creator =
				clientHelper.parseAndValidateAuthor(creatorList);
		byte[] creatorPub = creator.getPublicKey().getEncoded();
		byte[] creatorMlDsaPub = lookupAuthorMlDsaPub(txn, creatorPub);
		DIAG.info("enforceInviteHybridLock creator.pub.len="
				+ creatorPub.length
				+ " creatorMlDsaPub=" + (creatorMlDsaPub == null ? "null"
						: ("present:" + creatorMlDsaPub.length + "B")));
		if (creatorMlDsaPub == null) {
			DIAG.info("enforceInviteHybridLock SKIP (peer has no ML-DSA)");
			return;
		}
		byte[] sig = body.getRaw(5);
		DIAG.info("enforceInviteHybridLock sig.len=" + sig.length
				+ " expected=" + org.briarproject.bramble.api.crypto
						.PostQuantumConstants.HYBRID_SIGNATURE_BYTES);
		if (sig.length != org.briarproject.bramble.api.crypto
				.PostQuantumConstants.HYBRID_SIGNATURE_BYTES) {
			DIAG.severe("enforceInviteHybridLock REJECT: sig length mismatch");
			throw new FormatException();
		}
		PrivateGroup pg = privateGroupFactory.createPrivateGroup(
				body.getString(2), creator, body.getRaw(3));
		BdfList signed = BdfList.of(m.getTimestamp(), m.getGroupId(),
				pg.getId());
		HybridSignaturePublicKey hpk = new HybridSignaturePublicKey(
				creatorPub, creatorMlDsaPub);
		try {
			byte[] signedBytes = clientHelper.toByteArray(signed);
			DIAG.info("enforceInviteHybridLock signedBytes.len="
					+ signedBytes.length
					+ " sha256-prefix=" + java.util.HexFormat.of().formatHex(
							java.util.Arrays.copyOf(
									java.security.MessageDigest
											.getInstance("SHA-256")
											.digest(signedBytes), 8)));
			boolean ok = crypto.verifySignature(sig,
					org.briarproject.briar.api.privategroup.invitation
							.GroupInvitationFactory.SIGNING_LABEL_INVITE,
					signedBytes, hpk);
			if (!ok) {
				DIAG.severe("enforceInviteHybridLock REJECT: hybrid verify "
						+ "returned false");
				throw new FormatException();
			}
			DIAG.info("enforceInviteHybridLock hybrid verify PASS");
		} catch (java.security.GeneralSecurityException e) {
			DIAG.severe("enforceInviteHybridLock REJECT: " + e);
			throw new FormatException();
		}
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		Group localGroup = contactGroupFactory.createLocalGroup(CLIENT_ID,
				MAJOR_VERSION);
		if (db.containsGroup(txn, localGroup.getId())) return;
		db.addGroup(txn, localGroup);
		for (Contact c : db.getContacts(txn)) addingContact(txn, c);
	}

	@Override
	public void addingContact(Transaction txn, Contact c) throws DbException {
		Group g = getContactGroup(c);
		db.addGroup(txn, g);
		Visibility client = clientVersioningManager.getClientVisibility(txn,
				c.getId(), CLIENT_ID, MAJOR_VERSION);
		db.setGroupVisibility(txn, c.getId(), g.getId(), client);
		clientHelper.setContactId(txn, g.getId(), c.getId());
		for (Group group : db.getGroups(txn, PrivateGroupManager.CLIENT_ID,
				PrivateGroupManager.MAJOR_VERSION)) {
			if (privateGroupManager
					.isMember(txn, group.getId(), c.getAuthor())) {
				PrivateGroup pg =
						privateGroupManager.getPrivateGroup(txn, group.getId());
				recreateSession(txn, c, pg, g.getId());
			}
		}
	}

	private void recreateSession(Transaction txn, Contact c,
			PrivateGroup pg, GroupId contactGroupId) throws DbException {
		boolean isOur = privateGroupManager.isOurPrivateGroup(txn, pg);
		boolean isTheirs =
				c.getAuthor().getId().equals(pg.getCreator().getId());
		if (isOur || isTheirs) {
			MessageId storageId = createStorageId(txn, contactGroupId);
			Session<?> session;
			if (isOur) {
				session = new CreatorSession(contactGroupId, pg.getId(), null,
						null, 0, 0, CreatorState.LEFT);
			} else {
				session = new InviteeSession(contactGroupId, pg.getId(), null,
						null, 0, 0, InviteeState.LEFT);
			}
			try {
				storeSession(txn, storageId, session);
			} catch (FormatException e) {
				throw new DbException(e);
			}
		} else {
			addingMember(txn, pg.getId(), c);
		}
	}

	@Override
	public void removingContact(Transaction txn, Contact c) throws DbException {
		for (Group g : db.getGroups(txn, PrivateGroupManager.CLIENT_ID,
				PrivateGroupManager.MAJOR_VERSION)) {
			if (privateGroupManager.isMember(txn, g.getId(), c.getAuthor())) {
				PrivateGroup pg =
						privateGroupManager.getPrivateGroup(txn, g.getId());
				if (c.getAuthor().getId().equals(pg.getCreator().getId())) {
					privateGroupManager.markGroupDissolved(txn, g.getId());
				}
			}
		}
		db.removeGroup(txn, getContactGroup(c));
	}

	@Override
	public Group getContactGroup(Contact c) {
		return contactGroupFactory.createContactGroup(CLIENT_ID,
				MAJOR_VERSION, c);
	}

	@Override
	protected DeliveryAction incomingMessage(Transaction txn, Message m,
			BdfList body, BdfDictionary bdfMeta)
			throws DbException, FormatException {
		enforceInviteHybridLock(txn, m, body);
		MessageMetadata meta = messageParser.parseMetadata(bdfMeta);
		long timer = meta.getAutoDeleteTimer();
		if (timer != NO_AUTO_DELETE_TIMER) {
			db.setCleanupTimerDuration(txn, m.getId(), timer);
		}
		SessionId sessionId = getSessionId(meta.getPrivateGroupId());
		StoredSession ss = getSession(txn, m.getGroupId(), sessionId);
		Session<?> session;
		MessageId storageId;
		if (ss == null) {
			session = handleFirstMessage(txn, m, body, meta);
			storageId = createStorageId(txn, m.getGroupId());
		} else {
			session = handleMessage(txn, m, body, meta, ss.bdfSession);
			storageId = ss.storageId;
		}
		storeSession(txn, storageId, session);
		return ACCEPT_DO_NOT_SHARE;
	}

	private SessionId getSessionId(GroupId privateGroupId) {
		return new SessionId(privateGroupId.getBytes());
	}

	@Nullable
	private StoredSession getSession(Transaction txn, GroupId contactGroupId,
			SessionId sessionId) throws DbException, FormatException {
		BdfDictionary query = sessionParser.getSessionQuery(sessionId);
		Map<MessageId, BdfDictionary> results = clientHelper
				.getMessageMetadataAsDictionary(txn, contactGroupId, query);
		if (results.size() > 1) throw new DbException();
		if (results.isEmpty()) return null;
		return new StoredSession(results.keySet().iterator().next(),
				results.values().iterator().next());
	}

	private Session<?> handleFirstMessage(Transaction txn, Message m,
			BdfList body, MessageMetadata meta)
			throws DbException, FormatException {
		GroupId privateGroupId = meta.getPrivateGroupId();
		MessageType type = meta.getMessageType();
		if (type == INVITE) {
			InviteeSession session =
					new InviteeSession(m.getGroupId(), privateGroupId);
			return handleMessage(txn, m, body, type, session, inviteeEngine);
		} else if (type == JOIN) {
			PeerSession session =
					new PeerSession(m.getGroupId(), privateGroupId);
			return handleMessage(txn, m, body, type, session, peerEngine);
		} else {
			throw new FormatException();
		}
	}

	private Session<?> handleMessage(Transaction txn, Message m, BdfList body,
			MessageMetadata meta, BdfDictionary bdfSession)
			throws DbException, FormatException {
		MessageType type = meta.getMessageType();
		Role role = sessionParser.getRole(bdfSession);
		if (role == CREATOR) {
			CreatorSession session = sessionParser
					.parseCreatorSession(m.getGroupId(), bdfSession);
			return handleMessage(txn, m, body, type, session, creatorEngine);
		} else if (role == INVITEE) {
			InviteeSession session = sessionParser
					.parseInviteeSession(m.getGroupId(), bdfSession);
			return handleMessage(txn, m, body, type, session, inviteeEngine);
		} else if (role == PEER) {
			PeerSession session = sessionParser
					.parsePeerSession(m.getGroupId(), bdfSession);
			return handleMessage(txn, m, body, type, session, peerEngine);
		} else {
			throw new AssertionError();
		}
	}

	private <S extends Session<?>> S handleMessage(Transaction txn, Message m,
			BdfList body, MessageType type, S session, ProtocolEngine<S> engine)
			throws DbException, FormatException {
		if (type == INVITE) {
			InviteMessage invite = messageParser.parseInviteMessage(m, body);
			return engine.onInviteMessage(txn, session, invite);
		} else if (type == JOIN) {
			JoinMessage join = messageParser.parseJoinMessage(m, body);
			return engine.onJoinMessage(txn, session, join);
		} else if (type == LEAVE) {
			LeaveMessage leave = messageParser.parseLeaveMessage(m, body);
			return engine.onLeaveMessage(txn, session, leave);
		} else if (type == ABORT) {
			AbortMessage abort = messageParser.parseAbortMessage(m, body);
			return engine.onAbortMessage(txn, session, abort);
		} else {
			throw new AssertionError();
		}
	}

	private MessageId createStorageId(Transaction txn, GroupId g)
			throws DbException {
		Message m = clientHelper.createMessageForStoringMetadata(g);
		db.addLocalMessage(txn, m, new Metadata(), false, false);
		return m.getId();
	}

	private void storeSession(Transaction txn, MessageId storageId,
			Session<?> session) throws DbException, FormatException {
		BdfDictionary d = sessionEncoder.encodeSession(session);
		clientHelper.mergeMessageMetadata(txn, storageId, d);
	}

	@Override
	public void sendInvitation(GroupId privateGroupId, ContactId c,
			@Nullable String text, long timestamp, byte[] signature,
			long autoDeleteTimer) throws DbException {
		db.transaction(false,
				txn -> sendInvitation(txn, privateGroupId, c, text, timestamp,
						signature, autoDeleteTimer));
	}

	@Override
	public void sendInvitation(Transaction txn, GroupId privateGroupId,
			ContactId c, @Nullable String text, long timestamp,
			byte[] signature, long autoDeleteTimer) throws DbException {
		SessionId sessionId = getSessionId(privateGroupId);
		try {
			Contact contact = db.getContact(txn, c);
			GroupId contactGroupId = getContactGroup(contact).getId();
			StoredSession ss = getSession(txn, contactGroupId, sessionId);
			CreatorSession session;
			MessageId storageId;
			if (ss == null) {
				session = new CreatorSession(contactGroupId, privateGroupId);
				storageId = createStorageId(txn, contactGroupId);
			} else {
				session = sessionParser
						.parseCreatorSession(contactGroupId, ss.bdfSession);
				storageId = ss.storageId;
			}
			session = creatorEngine.onInviteAction(txn, session, text,
					timestamp, signature, autoDeleteTimer);
			storeSession(txn, storageId, session);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void respondToInvitation(ContactId c, PrivateGroup g, boolean accept)
			throws DbException {
		respondToInvitation(c, getSessionId(g.getId()), accept);
	}

	@Override
	public void respondToInvitation(Transaction txn, ContactId c,
			PrivateGroup g, boolean accept) throws DbException {
		respondToInvitation(txn, c, getSessionId(g.getId()), accept);
	}

	@Override
	public void respondToInvitation(ContactId c, SessionId sessionId,
			boolean accept) throws DbException {
		db.transaction(false,
				txn -> respondToInvitation(txn, c, sessionId, accept, false));
	}

	@Override
	public void respondToInvitation(Transaction txn, ContactId c,
			SessionId sessionId, boolean accept) throws DbException {
		respondToInvitation(txn, c, sessionId, accept, false);
	}

	private void respondToInvitation(Transaction txn, ContactId c,
			SessionId sessionId, boolean accept, boolean isAutoDecline)
			throws DbException {
		try {
			Contact contact = db.getContact(txn, c);
			GroupId contactGroupId = getContactGroup(contact).getId();
			StoredSession ss = getSession(txn, contactGroupId, sessionId);
			if (ss == null) throw new IllegalArgumentException();
			InviteeSession session = sessionParser
					.parseInviteeSession(contactGroupId, ss.bdfSession);
			if (accept) session = inviteeEngine.onJoinAction(txn, session);
			else session =
					inviteeEngine.onLeaveAction(txn, session, isAutoDecline);
			storeSession(txn, ss.storageId, session);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void revealRelationship(ContactId c, GroupId g) throws DbException {
		db.transaction(false, txn -> revealRelationship(txn, c, g));
	}

	@Override
	public void revealRelationship(Transaction txn, ContactId c, GroupId g)
			throws DbException {
		try {
			Contact contact = db.getContact(txn, c);
			GroupId contactGroupId = getContactGroup(contact).getId();
			StoredSession ss = getSession(txn, contactGroupId, getSessionId(g));
			if (ss == null) throw new IllegalArgumentException();
			PeerSession session = sessionParser
					.parsePeerSession(contactGroupId, ss.bdfSession);
			session = peerEngine.onJoinAction(txn, session);
			storeSession(txn, ss.storageId, session);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private <S extends Session<?>> S handleAction(Transaction txn,
			LocalAction type, S session, ProtocolEngine<S> engine)
			throws DbException {
		if (type == LocalAction.INVITE) {
			throw new IllegalArgumentException();
		} else if (type == LocalAction.JOIN) {
			return engine.onJoinAction(txn, session);
		} else if (type == LocalAction.LEAVE) {
			return engine.onLeaveAction(txn, session, false);
		} else if (type == LocalAction.MEMBER_ADDED) {
			return engine.onMemberAddedAction(txn, session);
		} else {
			throw new AssertionError();
		}
	}

	@Override
	public Collection<ConversationMessageHeader> getMessageHeaders(
			Transaction txn,
			ContactId c) throws DbException {
		try {
			Contact contact = db.getContact(txn, c);
			GroupId contactGroupId = getContactGroup(contact).getId();
			BdfDictionary query = messageParser.getMessagesVisibleInUiQuery();
			Map<MessageId, BdfDictionary> results = clientHelper
					.getMessageMetadataAsDictionary(txn, contactGroupId, query);
			List<ConversationMessageHeader> messages =
					new ArrayList<>(results.size());
			for (Entry<MessageId, BdfDictionary> e : results.entrySet()) {
				MessageId m = e.getKey();
				MessageMetadata meta =
						messageParser.parseMetadata(e.getValue());
				MessageStatus status = db.getMessageStatus(txn, c, m);
				MessageType type = meta.getMessageType();
				if (type == INVITE) {
					messages.add(parseInvitationRequest(txn, contactGroupId, m,
							meta, status));
				} else if (type == JOIN) {
					messages.add(parseInvitationResponse(contactGroupId, m,
							meta, status, true));
				} else if (type == LEAVE) {
					messages.add(parseInvitationResponse(contactGroupId, m,
							meta, status, false));
				}
			}
			return messages;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private GroupInvitationRequest parseInvitationRequest(Transaction txn,
			GroupId contactGroupId, MessageId m, MessageMetadata meta,
			MessageStatus status) throws DbException, FormatException {
		SessionId sessionId = getSessionId(meta.getPrivateGroupId());
		InviteMessage invite = messageParser.getInviteMessage(txn, m);
		PrivateGroup pg = privateGroupFactory
				.createPrivateGroup(invite.getGroupName(), invite.getCreator(),
						invite.getSalt());
		boolean canBeOpened = meta.wasAccepted() &&
				db.containsGroup(txn, invite.getPrivateGroupId());
		return new GroupInvitationRequest(m, contactGroupId,
				meta.getTimestamp(), meta.isLocal(), meta.isRead(),
				status.isSent(), status.isSeen(), sessionId, pg,
				invite.getText(), meta.isAvailableToAnswer(), canBeOpened,
				invite.getAutoDeleteTimer());
	}

	private GroupInvitationResponse parseInvitationResponse(
			GroupId contactGroupId, MessageId m, MessageMetadata meta,
			MessageStatus status, boolean accept) {
		SessionId sessionId = getSessionId(meta.getPrivateGroupId());
		return new GroupInvitationResponse(m, contactGroupId,
				meta.getTimestamp(), meta.isLocal(), meta.isRead(),
				status.isSent(), status.isSeen(), sessionId, accept,
				meta.getPrivateGroupId(), meta.getAutoDeleteTimer(),
				meta.isAutoDecline());
	}

	@Override
	public Collection<GroupInvitationItem> getInvitations() throws DbException {
		return db.transactionWithResult(true, this::getInvitations);
	}

	@Override
	public Collection<GroupInvitationItem> getInvitations(Transaction txn)
			throws DbException {
		List<GroupInvitationItem> items = new ArrayList<>();
		BdfDictionary query = messageParser.getInvitesAvailableToAnswerQuery();
		try {
			for (Contact c : db.getContacts(txn)) {
				GroupId contactGroupId = getContactGroup(c).getId();
				Collection<MessageId> results = clientHelper.getMessageIds(txn,
						contactGroupId, query);
				for (MessageId m : results)
					items.add(parseGroupInvitationItem(txn, c, m));
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
		return items;
	}

	@Override
	public SharingStatus getSharingStatus(Contact c, GroupId privateGroupId)
			throws DbException {
		return db.transactionWithResult(true,
				txn -> getSharingStatus(txn, c, privateGroupId));
	}

	@Override
	public SharingStatus getSharingStatus(Transaction txn, Contact c,
			GroupId privateGroupId) throws DbException {
		GroupId contactGroupId = getContactGroup(c).getId();
		SessionId sessionId = getSessionId(privateGroupId);
		try {
			Visibility client = clientVersioningManager.getClientVisibility(txn,
					c.getId(), PrivateGroupManager.CLIENT_ID,
					PrivateGroupManager.MAJOR_VERSION);
			StoredSession ss = getSession(txn, contactGroupId, sessionId);
			if (client != SHARED) return SharingStatus.NOT_SUPPORTED;
			if (ss == null) return SharingStatus.SHAREABLE;
			CreatorSession session = sessionParser
					.parseCreatorSession(contactGroupId, ss.bdfSession);
			CreatorState state = session.getState();
			if (state == START) return SharingStatus.SHAREABLE;
			if (state == INVITED) return SharingStatus.INVITE_SENT;
			if (state == JOINED) return SharingStatus.SHARING;
			if (state == CreatorState.LEFT) return SharingStatus.SHARING;
			if (state == DISSOLVED) throw new ProtocolStateException();
			if (state == ERROR) return SharingStatus.ERROR;
			throw new AssertionError("Unhandled state: " + state.name());
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private GroupInvitationItem parseGroupInvitationItem(Transaction txn,
			Contact c, MessageId m) throws DbException, FormatException {
		InviteMessage invite = messageParser.getInviteMessage(txn, m);
		PrivateGroup privateGroup = privateGroupFactory.createPrivateGroup(
				invite.getGroupName(), invite.getCreator(), invite.getSalt());
		return new GroupInvitationItem(privateGroup, c);
	}

	@Override
	public void addingMember(Transaction txn, GroupId privateGroupId, Author a)
			throws DbException {
		for (Contact c : db.getContactsByAuthorId(txn, a.getId()))
			addingMember(txn, privateGroupId, c);
	}

	private void addingMember(Transaction txn, GroupId privateGroupId,
			Contact c) throws DbException {
		try {
			GroupId contactGroupId = getContactGroup(c).getId();
			SessionId sessionId = getSessionId(privateGroupId);
			StoredSession ss = getSession(txn, contactGroupId, sessionId);
			Session<?> session;
			MessageId storageId;
			if (ss == null) {
				PeerSession peerSession =
						new PeerSession(contactGroupId, privateGroupId);
				session = peerEngine.onMemberAddedAction(txn, peerSession);
				storageId = createStorageId(txn, contactGroupId);
			} else {
				session = handleAction(txn, LocalAction.MEMBER_ADDED,
						contactGroupId, ss.bdfSession);
				storageId = ss.storageId;
			}
			storeSession(txn, storageId, session);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void removingGroup(Transaction txn, GroupId privateGroupId)
			throws DbException {
		SessionId sessionId = getSessionId(privateGroupId);
		try {
			for (Contact c : db.getContacts(txn)) {
				GroupId contactGroupId = getContactGroup(c).getId();
				StoredSession ss = getSession(txn, contactGroupId, sessionId);
				if (ss == null) continue;
				Session<?> session = handleAction(txn, LocalAction.LEAVE,
						contactGroupId, ss.bdfSession);
				storeSession(txn, ss.storageId, session);
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private Session<?> handleAction(Transaction txn, LocalAction a,
			GroupId contactGroupId, BdfDictionary bdfSession)
			throws DbException, FormatException {
		Role role = sessionParser.getRole(bdfSession);
		if (role == CREATOR) {
			CreatorSession session = sessionParser
					.parseCreatorSession(contactGroupId, bdfSession);
			return handleAction(txn, a, session, creatorEngine);
		} else if (role == INVITEE) {
			InviteeSession session = sessionParser
					.parseInviteeSession(contactGroupId, bdfSession);
			return handleAction(txn, a, session, inviteeEngine);
		} else if (role == PEER) {
			PeerSession session = sessionParser
					.parsePeerSession(contactGroupId, bdfSession);
			return handleAction(txn, a, session, peerEngine);
		} else {
			throw new AssertionError();
		}
	}

	@Override
	public void onClientVisibilityChanging(Transaction txn, Contact c,
			Visibility v) throws DbException {
		Group g = getContactGroup(c);
		db.setGroupVisibility(txn, c.getId(), g.getId(), v);
	}

	ClientVersioningHook getPrivateGroupClientVersioningHook() {
		return this::onPrivateGroupClientVisibilityChanging;
	}

	private void onPrivateGroupClientVisibilityChanging(Transaction txn,
			Contact c, Visibility client) throws DbException {
		try {
			Collection<Group> shareables =
					db.getGroups(txn, PrivateGroupManager.CLIENT_ID,
							PrivateGroupManager.MAJOR_VERSION);
			Map<GroupId, Visibility> m = getPreferredVisibilities(txn, c);
			for (Group g : shareables) {
				Visibility preferred = m.get(g.getId());
				if (preferred == null) continue;
				Visibility min = Visibility.min(preferred, client);
				db.setGroupVisibility(txn, c.getId(), g.getId(), min);
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private Map<GroupId, Visibility> getPreferredVisibilities(Transaction txn,
			Contact c) throws DbException, FormatException {
		GroupId contactGroupId = getContactGroup(c).getId();
		BdfDictionary query = sessionParser.getAllSessionsQuery();
		Map<MessageId, BdfDictionary> results = clientHelper
				.getMessageMetadataAsDictionary(txn, contactGroupId, query);
		Map<GroupId, Visibility> m = new HashMap<>();
		for (BdfDictionary d : results.values()) {
			Session<?> s = sessionParser.parseSession(contactGroupId, d);
			m.put(s.getPrivateGroupId(), s.getState().getVisibility());
		}
		return m;
	}

	@FunctionalInterface
	private interface DeletableSessionRetriever {
		Map<GroupId, DeletableSession> getDeletableSessions(Transaction txn,
				GroupId contactGroup, Map<MessageId, BdfDictionary> metadata)
				throws DbException;
	}

	@FunctionalInterface
	private interface MessageDeletionChecker {
		
		boolean causesProblem(MessageId messageId);
	}

	@Override
	public DeletionResult deleteAllMessages(Transaction txn, ContactId c)
			throws DbException {
		return deleteMessages(txn, c, (txn1, g, metadata) -> {
			Map<GroupId, DeletableSession> sessions = new HashMap<>();
			for (BdfDictionary d : metadata.values()) {
				Session<?> session;
				try {
					if (!sessionParser.isSession(d)) continue;
					session = sessionParser.parseSession(g, d);
				} catch (FormatException e) {
					throw new DbException(e);
				}
				sessions.put(session.getPrivateGroupId(),
						new DeletableSession(session.getState()));
			}
			return sessions;
		}, messageId -> false);
	}

	@Override
	public DeletionResult deleteMessages(Transaction txn, ContactId c,
			Set<MessageId> messageIds) throws DbException {
		return deleteMessages(txn, c, (txn1, g, metadata) -> {
			Map<GroupId, DeletableSession> sessions = new HashMap<>();
			for (MessageId messageId : messageIds) {
				BdfDictionary d = metadata.get(messageId);
				if (d == null) continue;
				try {
					MessageMetadata messageMetadata =
							messageParser.parseMetadata(d);
					SessionId sessionId =
							getSessionId(messageMetadata.getPrivateGroupId());
					StoredSession ss = getSession(txn1, g, sessionId);
					if (ss == null) throw new DbException();
					Session<?> session = sessionParser
							.parseSession(g, metadata.get(ss.storageId));
					sessions.put(session.getPrivateGroupId(),
							new DeletableSession(session.getState()));
				} catch (FormatException e) {
					throw new DbException(e);
				}
			}
			return sessions;
		}, messageId -> !messageIds.contains(messageId));
	}

	private DeletionResult deleteMessages(Transaction txn, ContactId c,
			DeletableSessionRetriever retriever, MessageDeletionChecker checker)
			throws DbException {
		GroupId g = getContactGroup(db.getContact(txn, c)).getId();
		Map<MessageId, BdfDictionary> metadata;
		try {
			metadata = clientHelper.getMessageMetadataAsDictionary(txn, g);
		} catch (FormatException e) {
			throw new DbException(e);
		}
		Map<GroupId, DeletableSession> sessions =
				retriever.getDeletableSessions(txn, g, metadata);
		for (Entry<MessageId, BdfDictionary> entry : metadata.entrySet()) {
			MessageMetadata m;
			try {
				BdfDictionary d = entry.getValue();
				if (sessionParser.isSession(d)) continue;
				m = messageParser.parseMetadata(d);
			} catch (FormatException e) {
				throw new DbException(e);
			}
			if (!m.isVisibleInConversation()) continue;
			DeletableSession session = sessions.get(m.getPrivateGroupId());
			if (session != null) session.messages.add(entry.getKey());
		}
		Set<MessageId> notAcked = new HashSet<>();
		for (MessageStatus status : db.getMessageStatus(txn, c, g)) {
			if (!status.isSeen()) notAcked.add(status.getMessageId());
		}
		DeletionResult result = deleteCompletedSessions(txn, sessions.values(),
				notAcked, checker);
		recalculateGroupCount(txn, g);
		return result;
	}

	private DeletionResult deleteCompletedSessions(Transaction txn,
			Collection<DeletableSession> sessions, Set<MessageId> notAcked,
			MessageDeletionChecker checker) throws DbException {
		DeletionResult result = new DeletionResult();
		for (DeletableSession session : sessions) {
			if (session.state.isAwaitingResponse()) {
				result.addInvitationSessionInProgress();
				continue;
			}
			boolean sessionDeletable = true;
			for (MessageId m : session.messages) {
				if (notAcked.contains(m) || checker.causesProblem(m)) {
					sessionDeletable = false;
					if (notAcked.contains(m))
						result.addInvitationSessionInProgress();
					if (checker.causesProblem(m))
						result.addInvitationNotAllSelected();
				}
			}
			if (sessionDeletable) {
				for (MessageId m : session.messages) {
					db.deleteMessage(txn, m);
					db.deleteMessageMetadata(txn, m);
				}
			}
		}
		return result;
	}

	@Override
	public void deleteMessages(Transaction txn, GroupId g,
			Collection<MessageId> messageIds) throws DbException {
		ContactId c;
		Map<SessionId, DeletableSession> sessions = new HashMap<>();
		try {
			c = clientHelper.getContactId(txn, g);
			for (MessageId messageId : messageIds) {
				BdfDictionary d = clientHelper
						.getMessageMetadataAsDictionary(txn, messageId);
				MessageMetadata messageMetadata =
						messageParser.parseMetadata(d);
				if (!messageMetadata.isVisibleInConversation())
					throw new IllegalArgumentException();
				SessionId sessionId =
						getSessionId(messageMetadata.getPrivateGroupId());
				DeletableSession deletableSession = sessions.get(sessionId);
				if (deletableSession == null) {
					StoredSession ss = getSession(txn, g, sessionId);
					if (ss == null) throw new DbException();
					Session<?> session =
							sessionParser.parseSession(g, ss.bdfSession);
					deletableSession = new DeletableSession(session.getState());
					sessions.put(sessionId, deletableSession);
				}
				deletableSession.messages.add(messageId);
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
		for (Entry<SessionId, DeletableSession> entry : sessions.entrySet()) {
			DeletableSession session = entry.getValue();
			if (session.state instanceof InviteeState &&
					session.state.isAwaitingResponse()) {
				respondToInvitation(txn, c, entry.getKey(), false, true);
			}
			for (MessageId m : session.messages) {
				db.deleteMessage(txn, m);
				db.deleteMessageMetadata(txn, m);
			}
		}
		recalculateGroupCount(txn, g);

		txn.attach(new ConversationMessagesDeletedEvent(c, messageIds));
	}

	@Override
	public Set<MessageId> getMessageIds(Transaction txn, ContactId c)
			throws DbException {
		GroupId g = getContactGroup(db.getContact(txn, c)).getId();
		BdfDictionary query = messageParser.getMessagesVisibleInUiQuery();
		try {
			return new HashSet<>(clientHelper.getMessageIds(txn, g, query));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void recalculateGroupCount(Transaction txn, GroupId g)
			throws DbException {
		BdfDictionary query = messageParser.getMessagesVisibleInUiQuery();
		Map<MessageId, BdfDictionary> results;
		try {
			results =
					clientHelper.getMessageMetadataAsDictionary(txn, g, query);
		} catch (FormatException e) {
			throw new DbException(e);
		}
		int msgCount = 0;
		int unreadCount = 0;
		for (Entry<MessageId, BdfDictionary> entry : results.entrySet()) {
			MessageMetadata meta;
			try {
				meta = messageParser.parseMetadata(entry.getValue());
			} catch (FormatException e) {
				throw new DbException(e);
			}
			msgCount++;
			if (!meta.isRead()) unreadCount++;
		}
		messageTracker.resetGroupCount(txn, g, msgCount, unreadCount);
	}

	private static class StoredSession {

		private final MessageId storageId;
		private final BdfDictionary bdfSession;

		private StoredSession(MessageId storageId, BdfDictionary bdfSession) {
			this.storageId = storageId;
			this.bdfSession = bdfSession;
		}
	}

	private static class DeletableSession {

		private final State state;
		private final List<MessageId> messages = new ArrayList<>();

		private DeletableSession(State state) {
			this.state = state;
		}
	}
}
