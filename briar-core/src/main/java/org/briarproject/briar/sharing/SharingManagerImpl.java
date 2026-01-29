package org.briarproject.briar.sharing;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.cleanup.CleanupHook;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager.ContactHook;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.sync.ClientId;
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
import org.briarproject.briar.api.conversation.ConversationRequest;
import org.briarproject.briar.api.conversation.DeletionResult;
import org.briarproject.briar.api.sharing.InvitationResponse;
import org.briarproject.briar.api.sharing.Shareable;
import org.briarproject.briar.api.sharing.SharingInvitationItem;
import org.briarproject.briar.api.sharing.SharingManager;
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

import static org.briarproject.bramble.api.sync.Group.Visibility.SHARED;
import static org.briarproject.bramble.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.briarproject.briar.api.sharing.SharingManager.SharingStatus.SHAREABLE;
import static org.briarproject.briar.sharing.MessageType.ABORT;
import static org.briarproject.briar.sharing.MessageType.ACCEPT;
import static org.briarproject.briar.sharing.MessageType.DECLINE;
import static org.briarproject.briar.sharing.MessageType.INVITE;
import static org.briarproject.briar.sharing.MessageType.LEAVE;
import static org.briarproject.briar.sharing.State.LOCAL_INVITED;
import static org.briarproject.briar.sharing.State.LOCAL_LEFT;
import static org.briarproject.briar.sharing.State.REMOTE_HANGING;
import static org.briarproject.briar.sharing.State.REMOTE_INVITED;
import static org.briarproject.briar.sharing.State.SHARING;
import static org.briarproject.briar.sharing.State.START;

@NotNullByDefault
abstract class SharingManagerImpl<S extends Shareable>
		extends ConversationClientImpl
		implements SharingManager<S>, OpenDatabaseHook, ContactHook,
		ClientVersioningHook, CleanupHook {

	private final ClientVersioningManager clientVersioningManager;
	private final MessageParser<S> messageParser;
	private final SessionEncoder sessionEncoder;
	private final SessionParser sessionParser;
	private final ContactGroupFactory contactGroupFactory;
	private final ProtocolEngine<S> engine;
	private final InvitationFactory<S, ?> invitationFactory;

	SharingManagerImpl(DatabaseComponent db, ClientHelper clientHelper,
			ClientVersioningManager clientVersioningManager,
			MetadataParser metadataParser, MessageParser<S> messageParser,
			SessionEncoder sessionEncoder, SessionParser sessionParser,
			MessageTracker messageTracker,
			ContactGroupFactory contactGroupFactory, ProtocolEngine<S> engine,
			InvitationFactory<S, ?> invitationFactory) {
		super(db, clientHelper, metadataParser, messageTracker);
		this.clientVersioningManager = clientVersioningManager;
		this.messageParser = messageParser;
		this.sessionEncoder = sessionEncoder;
		this.sessionParser = sessionParser;
		this.contactGroupFactory = contactGroupFactory;
		this.engine = engine;
		this.invitationFactory = invitationFactory;
	}

	protected abstract ClientId getClientId();

	protected abstract int getMajorVersion();

	protected abstract ClientId getShareableClientId();

	protected abstract int getShareableMajorVersion();

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		Group localGroup = contactGroupFactory.createLocalGroup(getClientId(),
				getMajorVersion());
		if (db.containsGroup(txn, localGroup.getId())) return;
		db.addGroup(txn, localGroup);
		for (Contact c : db.getContacts(txn)) addingContact(txn, c);
	}

	@Override
	public void addingContact(Transaction txn, Contact c) throws DbException {
		Group g = getContactGroup(c);
		db.addGroup(txn, g);
		Visibility client = clientVersioningManager.getClientVisibility(txn,
				c.getId(), getClientId(), getMajorVersion());
		db.setGroupVisibility(txn, c.getId(), g.getId(), client);
		clientHelper.setContactId(txn, g.getId(), c.getId());
	}

	@Override
	public void removingContact(Transaction txn, Contact c) throws DbException {
		db.removeGroup(txn, getContactGroup(c));
	}

	@Override
	public Group getContactGroup(Contact c) {
		return contactGroupFactory.createContactGroup(getClientId(),
				getMajorVersion(), c);
	}

	@Override
	protected DeliveryAction incomingMessage(Transaction txn, Message m,
			BdfList body, BdfDictionary d) throws DbException, FormatException {
		MessageMetadata meta = messageParser.parseMetadata(d);
		long timer = meta.getAutoDeleteTimer();
		if (timer != NO_AUTO_DELETE_TIMER) {
			db.setCleanupTimerDuration(txn, m.getId(), timer);
		}
		SessionId sessionId = getSessionId(meta.getShareableId());
		StoredSession ss = getSession(txn, m.getGroupId(), sessionId);
		Session session;
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

	
	void preShareGroup(Transaction txn, Contact c, Group g)
			throws DbException, FormatException {
		GroupId contactGroupId = getContactGroup(c).getId();
		StoredSession existingSession = getSession(txn, contactGroupId,
				getSessionId(g.getId()));
		if (existingSession != null) return;
		db.addGroup(txn, g);
		Visibility client = clientVersioningManager.getClientVisibility(txn,
				c.getId(), getShareableClientId(), getShareableMajorVersion());
		db.setGroupVisibility(txn, c.getId(), g.getId(), client);
		Session session = new Session(SHARING, contactGroupId, g.getId(),
				null, null, 0, 0);
		MessageId storageId = createStorageId(txn, contactGroupId);
		storeSession(txn, storageId, session);
	}

	private SessionId getSessionId(GroupId shareableId) {
		return new SessionId(shareableId.getBytes());
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

	private Session handleFirstMessage(Transaction txn, Message m, BdfList body,
			MessageMetadata meta) throws DbException, FormatException {
		GroupId shareableId = meta.getShareableId();
		MessageType type = meta.getMessageType();
		if (type == INVITE) {
			Session session = new Session(m.getGroupId(), shareableId);
			BdfDictionary d = sessionEncoder.encodeSession(session);
			return handleMessage(txn, m, body, meta, d);
		} else {
			throw new FormatException();
		}
	}

	private Session handleMessage(Transaction txn, Message m, BdfList body,
			MessageMetadata meta, BdfDictionary d)
			throws DbException, FormatException {
		MessageType type = meta.getMessageType();
		Session session = sessionParser.parseSession(m.getGroupId(), d);
		if (type == INVITE) {
			InviteMessage<S> invite = messageParser.parseInviteMessage(m, body);
			return engine.onInviteMessage(txn, session, invite);
		} else if (type == ACCEPT) {
			AcceptMessage accept = messageParser.parseAcceptMessage(m, body);
			return engine.onAcceptMessage(txn, session, accept);
		} else if (type == DECLINE) {
			DeclineMessage decline = messageParser.parseDeclineMessage(m, body);
			return engine.onDeclineMessage(txn, session, decline);
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
			Session session) throws DbException, FormatException {
		BdfDictionary d = sessionEncoder.encodeSession(session);
		clientHelper.mergeMessageMetadata(txn, storageId, d);
	}

	@Override
	public void sendInvitation(GroupId shareableId, ContactId contactId,
			@Nullable String text) throws DbException {
		db.transaction(false,
				txn -> sendInvitation(txn, shareableId, contactId, text));
	}

	@Override
	public void sendInvitation(Transaction txn, GroupId shareableId,
			ContactId contactId, @Nullable String text) throws DbException {
		SessionId sessionId = getSessionId(shareableId);
		try {
			Contact contact = db.getContact(txn, contactId);
			if (getSharingStatus(txn, shareableId, contact) != SHAREABLE)
				return;
			GroupId contactGroupId = getContactGroup(contact).getId();
			StoredSession ss = getSession(txn, contactGroupId, sessionId);
			Session session;
			MessageId storageId;
			if (ss == null) {
				session = new Session(contactGroupId, shareableId);
				storageId = createStorageId(txn, contactGroupId);
			} else {
				session = sessionParser
						.parseSession(contactGroupId, ss.bdfSession);
				storageId = ss.storageId;
			}
			session = engine.onInviteAction(txn, session, text);
			storeSession(txn, storageId, session);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void respondToInvitation(S s, Contact c, boolean accept)
			throws DbException {
		respondToInvitation(c.getId(), getSessionId(s.getId()), accept);
	}

	@Override
	public void respondToInvitation(Transaction txn, S s, Contact c,
			boolean accept) throws DbException {
		respondToInvitation(txn, c.getId(), getSessionId(s.getId()), accept);
	}

	@Override
	public void respondToInvitation(ContactId c, SessionId id, boolean accept)
			throws DbException {
		db.transaction(false,
				txn -> respondToInvitation(txn, c, id, accept, false));
	}

	@Override
	public void respondToInvitation(Transaction txn, ContactId c, SessionId id,
			boolean accept) throws DbException {
		respondToInvitation(txn, c, id, accept, false);
	}

	private void respondToInvitation(Transaction txn, ContactId c,
			SessionId id, boolean accept, boolean isAutoDecline)
			throws DbException {
		try {
			Contact contact = db.getContact(txn, c);
			GroupId contactGroupId = getContactGroup(contact).getId();
			StoredSession ss = getSession(txn, contactGroupId, id);
			if (ss == null) throw new IllegalArgumentException();
			Session session =
					sessionParser.parseSession(contactGroupId, ss.bdfSession);
			if (accept) session = engine.onAcceptAction(txn, session);
			else session = engine.onDeclineAction(txn, session, isAutoDecline);
			storeSession(txn, ss.storageId, session);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public Collection<ConversationMessageHeader> getMessageHeaders(
			Transaction txn, ContactId c) throws DbException {
		try {
			Contact contact = db.getContact(txn, c);
			GroupId contactGroupId = getContactGroup(contact).getId();
			BdfDictionary query = messageParser.getMessagesVisibleInUiQuery();
			Map<MessageId, BdfDictionary> results = clientHelper
					.getMessageMetadataAsDictionary(txn, contactGroupId, query);
			Collection<ConversationMessageHeader> messages =
					new ArrayList<>(results.size());
			for (Entry<MessageId, BdfDictionary> e : results.entrySet()) {
				MessageId m = e.getKey();
				MessageMetadata meta =
						messageParser.parseMetadata(e.getValue());
				MessageStatus status = db.getMessageStatus(txn, c, m);
				MessageType type = meta.getMessageType();
				if (type == INVITE) {
					messages.add(parseInvitationRequest(txn, c, m,
							meta, status));
				} else if (type == ACCEPT) {
					messages.add(parseInvitationResponse(contactGroupId, m,
							meta, status, true));
				} else if (type == DECLINE) {
					messages.add(parseInvitationResponse(contactGroupId, m,
							meta, status, false));
				}
			}
			return messages;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private ConversationRequest<S> parseInvitationRequest(Transaction txn,
			ContactId c, MessageId m, MessageMetadata meta,
			MessageStatus status) throws DbException, FormatException {
		InviteMessage<S> invite = messageParser.getInviteMessage(txn, m);
		boolean canBeOpened = meta.wasAccepted() &&
				db.containsGroup(txn, invite.getShareableId());
		return invitationFactory
				.createInvitationRequest(meta.isLocal(), status.isSent(),
						status.isSeen(), meta.isRead(), invite, c,
						meta.isAvailableToAnswer(), canBeOpened,
						meta.getAutoDeleteTimer());
	}

	private InvitationResponse parseInvitationResponse(GroupId contactGroupId,
			MessageId m, MessageMetadata meta, MessageStatus status,
			boolean accept) {
		return invitationFactory.createInvitationResponse(m, contactGroupId,
				meta.getTimestamp(), meta.isLocal(), status.isSent(),
				status.isSeen(), meta.isRead(), accept, meta.getShareableId(),
				meta.getAutoDeleteTimer(), meta.isAutoDecline());
	}

	@Override
	public Collection<SharingInvitationItem> getInvitations()
			throws DbException {
		return db.transactionWithResult(true, this::getInvitations);
	}

	@Override
	public Collection<SharingInvitationItem> getInvitations(Transaction txn)
			throws DbException {
		List<SharingInvitationItem> items = new ArrayList<>();
		BdfDictionary query = messageParser.getInvitesAvailableToAnswerQuery();
		Map<S, Collection<Contact>> sharers = new HashMap<>();
		try {
			for (Contact c : db.getContacts(txn)) {
				GroupId contactGroupId = getContactGroup(c).getId();
				Map<MessageId, BdfDictionary> results =
						clientHelper.getMessageMetadataAsDictionary(txn,
								contactGroupId, query);
				for (MessageId m : results.keySet()) {
					InviteMessage<S> invite =
							messageParser.getInviteMessage(txn, m);
					S s = invite.getShareable();
					if (sharers.containsKey(s)) {
						sharers.get(s).add(c);
					} else {
						Collection<Contact> contacts = new ArrayList<>();
						contacts.add(c);
						sharers.put(s, contacts);
					}
				}
			}
			for (Entry<S, Collection<Contact>> e : sharers.entrySet()) {
				S s = e.getKey();
				Collection<Contact> contacts = e.getValue();
				boolean subscribed = db.containsGroup(txn, s.getId());
				SharingInvitationItem invitation =
						new SharingInvitationItem(s, subscribed, contacts);
				items.add(invitation);
			}
			return items;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public Collection<Contact> getSharedWith(GroupId g) throws DbException {
		return db.transactionWithResult(true, txn -> getSharedWith(txn, g));
	}

	@Override
	public Collection<Contact> getSharedWith(Transaction txn, GroupId g)
			throws DbException {
		Collection<Contact> contacts = new ArrayList<>();
		for (Contact c : db.getContacts(txn)) {
			if (db.getGroupVisibility(txn, c.getId(), g) == SHARED)
				contacts.add(c);
		}
		return contacts;
	}

	@Override
	public SharingStatus getSharingStatus(GroupId g, Contact c)
			throws DbException {
		Transaction txn = db.startTransaction(true);
		try {
			SharingStatus sharingStatus = getSharingStatus(txn, g, c);
			db.commitTransaction(txn);
			return sharingStatus;
		} finally {
			db.endTransaction(txn);
		}
	}

	@Override
	public SharingStatus getSharingStatus(Transaction txn, GroupId g, Contact c)
			throws DbException {
		Visibility client = clientVersioningManager.getClientVisibility(txn,
				c.getId(), getShareableClientId(), getShareableMajorVersion());
		if (client != SHARED) return SharingStatus.NOT_SUPPORTED;
		GroupId contactGroupId = getContactGroup(c).getId();
		SessionId sessionId = getSessionId(g);
		try {
			StoredSession ss = getSession(txn, contactGroupId, sessionId);
			if (ss == null) return SharingStatus.SHAREABLE;
			Session session =
					sessionParser.parseSession(contactGroupId, ss.bdfSession);
			State state = session.getState();
			if (state == START) return SharingStatus.SHAREABLE;
			if (state == LOCAL_INVITED) return SharingStatus.INVITE_RECEIVED;
			if (state == REMOTE_INVITED) return SharingStatus.INVITE_SENT;
			if (state == SHARING) return SharingStatus.SHARING;
			if (state == LOCAL_LEFT || state == REMOTE_HANGING)
				throw new ProtocolStateException();
			throw new AssertionError("Unhandled state: " + state.name());
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	void removingShareable(Transaction txn, S shareable) throws DbException {
		SessionId sessionId = getSessionId(shareable.getId());
		try {
			for (Contact c : db.getContacts(txn)) {
				GroupId contactGroupId = getContactGroup(c).getId();
				StoredSession ss = getSession(txn, contactGroupId, sessionId);
				if (ss == null) continue;
				Session session = sessionParser
						.parseSession(contactGroupId, ss.bdfSession);
				session = engine.onLeaveAction(txn, session);
				storeSession(txn, ss.storageId, session);
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public void onClientVisibilityChanging(Transaction txn, Contact c,
			Visibility v) throws DbException {
		Group g = getContactGroup(c);
		db.setGroupVisibility(txn, c.getId(), g.getId(), v);
	}

	ClientVersioningHook getShareableClientVersioningHook() {
		return this::onShareableClientVisibilityChanging;
	}
	private void onShareableClientVisibilityChanging(Transaction txn, Contact c,
			Visibility client) throws DbException {
		try {
			Collection<Group> shareables = db.getGroups(txn,
					getShareableClientId(), getShareableMajorVersion());
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
			Session s = sessionParser.parseSession(contactGroupId, d);
			m.put(s.getShareableId(), s.getState().getVisibility());
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
		return deleteMessages(txn, c, (txn1, contactGroup, metadata) -> {
			Map<GroupId, DeletableSession> sessions = new HashMap<>();
			for (BdfDictionary d : metadata.values()) {
				Session session;
				try {
					if (!sessionParser.isSession(d)) continue;
					session = sessionParser.parseSession(contactGroup, d);
				} catch (FormatException e) {
					throw new DbException(e);
				}
				sessions.put(session.getShareableId(),
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
							getSessionId(messageMetadata.getShareableId());
					StoredSession ss = getSession(txn1, g, sessionId);
					if (ss == null) throw new DbException();
					Session session = sessionParser
							.parseSession(g, metadata.get(ss.storageId));
					sessions.put(session.getShareableId(),
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
			DeletableSession session = sessions.get(m.getShareableId());
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
						getSessionId(messageMetadata.getShareableId());
				DeletableSession deletableSession = sessions.get(sessionId);
				if (deletableSession == null) {
					StoredSession ss = getSession(txn, g, sessionId);
					if (ss == null) throw new DbException();
					Session session = sessionParser
							.parseSession(g, ss.bdfSession);
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
			if (session.state == LOCAL_INVITED) {
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
