package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.cleanup.CleanupHook;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.client.ContactGroupFactory;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager.ContactHook;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataParser;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Metadata;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.Group.Visibility;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.InvalidMessageException;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.event.MessagesSentEvent;
import org.zerionproject.core.api.sync.event.MessagesAckedEvent;
import org.zerionproject.core.api.sync.MessageStatus;
import org.zerionproject.core.api.sync.validation.IncomingMessageHook;
import org.zerionproject.core.api.versioning.ClientVersioningManager;
import org.zerionproject.core.api.versioning.ClientVersioningManager.ClientVersioningHook;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.FileTooBigException;
import org.zerionproject.app.api.autodelete.AutoDeleteManager;
import org.zerionproject.app.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.zerionproject.app.api.client.MessageTracker;
import org.zerionproject.app.api.client.MessageTracker.GroupCount;
import org.zerionproject.app.api.conversation.ConversationManager;
import org.zerionproject.app.api.conversation.ConversationManager.ConversationClient;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.conversation.DeletionResult;
import org.zerionproject.app.api.messaging.LinkPreview;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessage;
import org.zerionproject.app.api.messaging.PrivateMessageFormat;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.app.api.messaging.VoiceSignal;
import org.zerionproject.app.api.messaging.VoiceSignalHeader;
import org.zerionproject.app.api.messaging.VoiceSignalType;
import org.zerionproject.app.api.messaging.event.AttachmentReceivedEvent;
import org.zerionproject.app.api.messaging.event.PrekeyBundleReceivedEvent;
import org.zerionproject.app.api.messaging.event.PrivateMessageReceivedEvent;
import org.zerionproject.app.api.messaging.event.ReactionReceivedEvent;
import org.zerionproject.app.api.messaging.event.TypingIndicatorReceivedEvent;
import org.zerionproject.app.api.messaging.event.VoiceSignalReceivedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.zerionproject.core.api.client.ContactGroupConstants.GROUP_KEY_CONTACT_ID;
import static org.zerionproject.core.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.zerionproject.core.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.zerionproject.core.util.IoUtils.copyAndClose;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.app.api.messaging.PrivateMessageFormat.TEXT_IMAGES;
import static org.zerionproject.app.api.messaging.PrivateMessageFormat.TEXT_IMAGES_AUTO_DELETE;
import static org.zerionproject.app.api.messaging.PrivateMessageFormat.TEXT_IMAGES_CHUNKED;
import static org.zerionproject.app.api.messaging.PrivateMessageFormat.TEXT_ONLY;
import static org.zerionproject.app.client.MessageTrackerConstants.MSG_KEY_READ;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT_CHUNK;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT_MANIFEST;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.app.messaging.MessagingConstants.MISSING_ATTACHMENT_CLEANUP_DURATION_MS;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_ATTACHMENT_HEADERS;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_AUTO_DELETE_TIMER;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_HAS_TEXT;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_IS_TYPING;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_LOCAL;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MESH;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MESH_STATE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MESH_SENDER_ID;
import static org.zerionproject.app.api.messaging.MessagingManager.MESH_STATE_PENDING;
import static org.zerionproject.app.api.messaging.MessagingManager.MESH_STATE_SENT;
import static org.zerionproject.app.api.messaging.MessagingManager.MESH_STATE_DELIVERED;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MSG_TYPE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_HAS_PREVIEW_IMAGE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_PREVIEW_DESCRIPTION;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_PREVIEW_TITLE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_PREVIEW_URL;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_REACTION_EMOJI;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_REPLY_TO_ID;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_TARGET_MESSAGE_ID;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_TIMESTAMP;

@Immutable
@NotNullByDefault
class MessagingManagerImpl implements MessagingManager, IncomingMessageHook,
		ConversationClient, OpenDatabaseHook, ContactHook,
		ClientVersioningHook, CleanupHook {
	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final MetadataParser metadataParser;
	private final ConversationManager conversationManager;
	private final MessageTracker messageTracker;
	private final ClientVersioningManager clientVersioningManager;
	private final ContactGroupFactory contactGroupFactory;
	private final AutoDeleteManager autoDeleteManager;
	private final StreamingAttachmentWriter streamingAttachmentWriter;
	private final IdentityManager identityManager;
	private final PrivateMessageValidator privateMessageValidator;

	@Inject
	MessagingManagerImpl(
			DatabaseComponent db,
			ClientHelper clientHelper,
			ClientVersioningManager clientVersioningManager,
			MetadataParser metadataParser,
			ConversationManager conversationManager,
			MessageTracker messageTracker,
			ContactGroupFactory contactGroupFactory,
			AutoDeleteManager autoDeleteManager,
			StreamingAttachmentWriter streamingAttachmentWriter,
			IdentityManager identityManager,
			PrivateMessageValidator privateMessageValidator) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.metadataParser = metadataParser;
		this.conversationManager = conversationManager;
		this.messageTracker = messageTracker;
		this.clientVersioningManager = clientVersioningManager;
		this.contactGroupFactory = contactGroupFactory;
		this.autoDeleteManager = autoDeleteManager;
		this.streamingAttachmentWriter = streamingAttachmentWriter;
		this.identityManager = identityManager;
		this.privateMessageValidator = privateMessageValidator;
	}

	@Override
	public GroupCount getGroupCount(Transaction txn, ContactId contactId)
			throws DbException {
		Contact contact = db.getContact(txn, contactId);
		GroupId groupId = getContactGroup(contact).getId();
		return messageTracker.getGroupCount(txn, groupId);
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		Group localGroup = contactGroupFactory.createLocalGroup(CLIENT_ID,
				MAJOR_VERSION);
		if (db.containsGroup(txn, localGroup.getId())) {
			purgeStaleEphemeralMessages(txn);
			return;
		}
		db.addGroup(txn, localGroup);
		for (Contact c : db.getContacts(txn)) addingContact(txn, c);
	}

	private static final long EPHEMERAL_PURGE_AGE_MS = 5 * 60 * 1000;

	private void purgeStaleEphemeralMessages(Transaction txn)
			throws DbException {
		try {
			long cutoff = System.currentTimeMillis() - EPHEMERAL_PURGE_AGE_MS;
			for (Contact c : db.getContacts(txn)) {
				GroupId gId = getContactGroup(c).getId();
				purgeByType(txn, gId, MessageTypes.VOICE_SIGNAL, cutoff);
				purgeByType(txn, gId, MessageTypes.TYPING_INDICATOR, cutoff);
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void purgeByType(Transaction txn, GroupId groupId,
			int messageType, long cutoff)
			throws DbException, FormatException {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(MSG_KEY_MSG_TYPE, messageType));
		Map<MessageId, BdfDictionary> matches =
				clientHelper.getMessageMetadataAsDictionary(
						txn, groupId, query);
		for (Map.Entry<MessageId, BdfDictionary> entry :
				matches.entrySet()) {
			long timestamp = entry.getValue().getLong(MSG_KEY_TIMESTAMP, 0L);
			if (timestamp >= cutoff) continue;
			try {
				db.removeMessage(txn, entry.getKey());
			} catch (NoSuchMessageException ignored) {
			}
		}
	}

	@Override
	public void addingContact(Transaction txn, Contact c) throws DbException {
		Group g = getContactGroup(c);
		db.addGroup(txn, g);
		Visibility client = clientVersioningManager.getClientVisibility(txn,
				c.getId(), CLIENT_ID, MAJOR_VERSION);
		db.setGroupVisibility(txn, c.getId(), g.getId(), client);
		clientHelper.setContactId(txn, g.getId(), c.getId());
		messageTracker.initializeGroupCount(txn, g.getId());
	}

	@Override
	public Group getContactGroup(Contact c) {
		return contactGroupFactory.createContactGroup(CLIENT_ID,
				MAJOR_VERSION, c);
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
	public DeliveryAction incomingMessage(Transaction txn, Message m,
			Metadata meta) throws DbException, InvalidMessageException {
		try {
			BdfDictionary metaDict = metadataParser.parse(meta);
			dispatchIncoming(txn, m, metaDict);
		} catch (FormatException e) {
			throw new InvalidMessageException(e);
		}
		return ACCEPT_DO_NOT_SHARE;
	}

	private void dispatchIncoming(Transaction txn, Message m,
			BdfDictionary metaDict)
			throws DbException, FormatException, InvalidMessageException {
		{
			Integer messageType = metaDict.getOptionalInt(MSG_KEY_MSG_TYPE);
			if (messageType == null) {
				incomingPrivateMessage(txn, m, metaDict, true, emptyList());
			} else if (messageType == PRIVATE_MESSAGE) {
				boolean hasText = metaDict.getBoolean(MSG_KEY_HAS_TEXT);
				List<AttachmentHeader> headers =
						parseAttachmentHeaders(m.getGroupId(), metaDict);
				incomingPrivateMessage(txn, m, metaDict, hasText, headers);
			} else if (messageType == ATTACHMENT) {
				incomingAttachment(txn, m);
			} else if (messageType == ATTACHMENT_MANIFEST) {
				incomingAttachmentManifest(txn, m);
			} else if (messageType == ATTACHMENT_CHUNK) {
				incomingAttachmentChunk(txn, m);
			} else if (messageType == MessageTypes.VOICE_SIGNAL) {
				incomingVoiceSignal(txn, m, metaDict);
			} else if (messageType == MessageTypes.MESSAGE_REACTION) {
				incomingReaction(txn, m, metaDict);
			} else if (messageType == MessageTypes.TYPING_INDICATOR) {
				incomingTypingIndicator(txn, m, metaDict);
			} else if (messageType == MessageTypes.LINK_PREVIEW_MESSAGE) {
				incomingLinkPreviewMessage(txn, m, metaDict);
			} else if (messageType == MessageTypes.MESH_PREKEY_BUNDLE) {
				incomingPrekeyBundle(txn, m);
			} else if (messageType == MessageTypes.GROUP_POST) {
				incomingGroupPost(txn, m, metaDict);
			} else if (messageType == MessageTypes.GROUP_MEMBER_ADDED
					|| messageType == MessageTypes.GROUP_MEMBER_REMOVED
					|| messageType == MessageTypes.GROUP_MEMBER_LEFT
					|| messageType == MessageTypes.GROUP_DISSOLVED
					|| messageType ==
							MessageTypes.GROUP_MEMBER_ROLE_CHANGED) {
				incomingGroupMembership(txn, m, metaDict, messageType);
			} else if (messageType == MessageTypes.GROUP_EPOCH_COMMIT) {
				incomingGroupEpochCommit(txn, m, metaDict);
			} else if (messageType ==
					MessageTypes.GROUP_MEMBER_LIST_SNAPSHOT) {
				incomingGroupMemberListSnapshot(txn, m, metaDict);
			} else if (messageType == MessageTypes.GROUPTR_INVITE_OFFER) {
				incomingGrouptrInviteOffer(txn, m, metaDict);
			} else if (messageType == MessageTypes.GROUPTR_INVITE_ACCEPT
					|| messageType == MessageTypes.GROUPTR_INVITE_DECLINE) {
				incomingGrouptrInviteResponse(txn, m, metaDict, messageType);
			} else {
				throw new InvalidMessageException();
			}
		}
	}

	private void incomingPrivateMessage(Transaction txn, Message m,
			BdfDictionary meta, boolean hasText, List<AttachmentHeader> headers)
			throws DbException, FormatException {
		GroupId groupId = m.getGroupId();
		long timestamp = meta.getLong(MSG_KEY_TIMESTAMP);
		boolean local = meta.getBoolean(MSG_KEY_LOCAL);
		boolean read = meta.getBoolean(MSG_KEY_READ);
		long timer = meta.getLong(MSG_KEY_AUTO_DELETE_TIMER,
				NO_AUTO_DELETE_TIMER);
		byte[] replyToIdBytes = meta.getOptionalRaw(MSG_KEY_REPLY_TO_ID);
		MessageId replyToId = replyToIdBytes != null ?
				new MessageId(replyToIdBytes) : null;
		PrivateMessageHeader header =
				new PrivateMessageHeader(m.getId(), groupId, timestamp, local,
						read, false, false, hasText, headers, timer,
						replyToId);
		ContactId contactId = getContactId(txn, groupId);
		PrivateMessageReceivedEvent event =
				new PrivateMessageReceivedEvent(header, contactId);
		txn.attach(event);
		conversationManager.trackIncomingMessage(txn, m);
		if (timer != NO_AUTO_DELETE_TIMER) {
			db.setCleanupTimerDuration(txn, m.getId(), timer);
		}
		autoDeleteManager.receiveAutoDeleteTimer(txn, contactId, timer,
				timestamp);
		if (!headers.isEmpty()) stopAttachmentCleanupTimers(txn, m, headers);
	}

	private List<AttachmentHeader> parseAttachmentHeaders(GroupId g,
			BdfDictionary meta) throws FormatException {
		BdfList attachmentHeaders = meta.getList(MSG_KEY_ATTACHMENT_HEADERS);
		int length = attachmentHeaders.size();
		List<AttachmentHeader> headers = new ArrayList<>(length);
		for (int i = 0; i < length; i++) {
			BdfList header = attachmentHeaders.getList(i);
			MessageId m = new MessageId(header.getRaw(0));
			String contentType = header.getString(1);
			headers.add(new AttachmentHeader(g, m, contentType));
		}
		return headers;
	}

	private void stopAttachmentCleanupTimers(Transaction txn, Message m,
			List<AttachmentHeader> headers)
			throws DbException, FormatException {
		BdfDictionary queryLegacy = BdfDictionary.of(
				new BdfEntry(MSG_KEY_MSG_TYPE, ATTACHMENT),
				new BdfEntry(MSG_KEY_LOCAL, false));
		Collection<MessageId> results = new HashSet<>(
				clientHelper.getMessageIds(txn, m.getGroupId(), queryLegacy));
		BdfDictionary queryManifest = BdfDictionary.of(
				new BdfEntry(MSG_KEY_MSG_TYPE, ATTACHMENT_MANIFEST),
				new BdfEntry(MSG_KEY_LOCAL, false));
		results.addAll(
				clientHelper.getMessageIds(txn, m.getGroupId(), queryManifest));
		for (AttachmentHeader h : headers) {
			MessageId id = h.getMessageId();
			if (results.contains(id)) db.stopCleanupTimer(txn, id);
		}
	}

	private void incomingAttachment(Transaction txn, Message m)
			throws DbException {
		ContactId contactId = getContactId(txn, m.getGroupId());
		txn.attach(new AttachmentReceivedEvent(m.getId(), contactId));
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE),
				new BdfEntry(MSG_KEY_LOCAL, false));
		try {
			Map<MessageId, BdfDictionary> results = clientHelper
					.getMessageMetadataAsDictionary(txn, m.getGroupId(), query);
			for (BdfDictionary meta : results.values()) {
				List<AttachmentHeader> headers =
						parseAttachmentHeaders(m.getGroupId(), meta);
				for (AttachmentHeader h : headers) {
					if (h.getMessageId().equals(m.getId())) return;
				}
			}
			db.setCleanupTimerDuration(txn, m.getId(),
					MISSING_ATTACHMENT_CLEANUP_DURATION_MS);
			db.startCleanupTimer(txn, m.getId());
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void incomingAttachmentManifest(Transaction txn, Message m)
			throws DbException {
		ContactId contactId = getContactId(txn, m.getGroupId());
		txn.attach(new AttachmentReceivedEvent(m.getId(), contactId));
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE),
				new BdfEntry(MSG_KEY_LOCAL, false));
		try {
			Map<MessageId, BdfDictionary> results = clientHelper
					.getMessageMetadataAsDictionary(txn, m.getGroupId(), query);
			for (BdfDictionary meta : results.values()) {
				List<AttachmentHeader> headers =
						parseAttachmentHeaders(m.getGroupId(), meta);
				for (AttachmentHeader h : headers) {
					if (h.getMessageId().equals(m.getId())) return;
				}
			}
			db.setCleanupTimerDuration(txn, m.getId(),
					MISSING_ATTACHMENT_CLEANUP_DURATION_MS);
			db.startCleanupTimer(txn, m.getId());
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void incomingAttachmentChunk(Transaction txn, Message m)
			throws DbException {
		ContactId contactId = getContactId(txn, m.getGroupId());
		txn.attach(new AttachmentReceivedEvent(m.getId(), contactId));
	}

	private void incomingVoiceSignal(Transaction txn, Message m,
			BdfDictionary meta) throws DbException, FormatException {
		GroupId groupId = m.getGroupId();
		long timestamp = meta.getLong(MSG_KEY_TIMESTAMP);
		boolean local = meta.getBoolean(MSG_KEY_LOCAL);
		BdfList body = clientHelper.getMessageAsList(txn, m.getId());
		if (body.size() < 3) {
			throw new FormatException();
		}

		int signalTypeValue = body.getInt(1);
		String callId = body.getString(2);
		String payload = body.getOptionalString(3);
		Long durationMs = body.getOptionalLong(4);

		VoiceSignalType signalType = VoiceSignalType.fromValue(signalTypeValue);
		VoiceSignalHeader header = new VoiceSignalHeader(
				m.getId(), groupId, timestamp, local,
				signalType, callId, payload, durationMs);
		ContactId contactId = getContactId(txn, groupId);
		VoiceSignalReceivedEvent event =
				new VoiceSignalReceivedEvent(header, contactId);
		txn.attach(event);

	}

	@Override
	public void addLocalMessage(PrivateMessage m) throws DbException {
		db.transaction(false, txn -> addLocalMessage(txn, m));
	}

	@Override
	public void addLocalMessage(Transaction txn, PrivateMessage m)
			throws DbException {
		addLocalMessage(txn, m, true);
	}

	@Override
	public void addLocalMeshMessage(Transaction txn, PrivateMessage m)
			throws DbException {
		addLocalMessage(txn, m, false, true);
	}

	private void addLocalMessage(Transaction txn, PrivateMessage m,
			boolean shared) throws DbException {
		addLocalMessage(txn, m, shared, false);
	}

	private void addLocalMessage(Transaction txn, PrivateMessage m,
			boolean shared, boolean mesh) throws DbException {
		try {
			long timer = m.getAutoDeleteTimer();
			BdfDictionary meta = new BdfDictionary();
			meta.put(MSG_KEY_TIMESTAMP, m.getMessage().getTimestamp());
			meta.put(MSG_KEY_LOCAL, true);
			meta.put(MSG_KEY_READ, true);
			if (mesh) {
				meta.put(MSG_KEY_MESH, true);
				meta.put(MSG_KEY_MESH_STATE, MESH_STATE_PENDING);
			}
			if (m.getFormat() != TEXT_ONLY) {
				meta.put(MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE);
				meta.put(MSG_KEY_HAS_TEXT, m.hasText());
				BdfList headers = new BdfList();
				for (AttachmentHeader a : m.getAttachmentHeaders()) {
					headers.add(
							BdfList.of(a.getMessageId(), a.getContentType()));
				}
				meta.put(MSG_KEY_ATTACHMENT_HEADERS, headers);
				if (m.getFormat() == TEXT_IMAGES_AUTO_DELETE
						&& timer != NO_AUTO_DELETE_TIMER) {
					meta.put(MSG_KEY_AUTO_DELETE_TIMER, timer);
				}
				if (m.getReplyToId() != null) {
					meta.put(MSG_KEY_REPLY_TO_ID,
							m.getReplyToId().getBytes());
				}
			}
			for (AttachmentHeader a : m.getAttachmentHeaders()) {
				db.setMessageShared(txn, a.getMessageId());
				db.setMessagePermanent(txn, a.getMessageId());
				shareAttachmentChunks(txn, a.getMessageId());
			}
			clientHelper.addLocalMessage(txn, m.getMessage(), meta, shared,
					false);
			if (timer != NO_AUTO_DELETE_TIMER) {
				db.setCleanupTimerDuration(txn, m.getMessage().getId(), timer);
			}
			conversationManager.trackOutgoingMessage(txn, m.getMessage());
		} catch (FormatException e) {
			throw new AssertionError(e);
		}
	}

	@Override
	public void receiveMeshMessage(ContactId contactId, String text,
			long timestamp, byte[] meshSenderId,
			@Nullable byte[] parentMeshSenderId) throws DbException {
		db.transaction(false, txn -> receiveMeshMessage(txn, contactId, text,
				timestamp, meshSenderId, parentMeshSenderId));
	}

	@Override
	public void receiveMeshMessage(Transaction txn, ContactId contactId,
			String text, long timestamp, byte[] meshSenderId,
			@Nullable byte[] parentMeshSenderId) throws DbException {
		try {
			GroupId groupId = getConversationId(txn, contactId);
			Message m = clientHelper.createMessage(groupId, timestamp,
					BdfList.of(text));
			BdfDictionary meta = new BdfDictionary();
			meta.put(MSG_KEY_TIMESTAMP, timestamp);
			meta.put(MSG_KEY_LOCAL, false);
			meta.put(MSG_KEY_READ, false);
			meta.put(MSG_KEY_MESH, true);
			meta.put(MSG_KEY_MESH_SENDER_ID, meshSenderId);
			MessageId replyToId = null;
			if (parentMeshSenderId != null) {
				replyToId = resolveMeshParent(txn, groupId, parentMeshSenderId);
				if (replyToId != null) {
					meta.put(MSG_KEY_REPLY_TO_ID, replyToId.getBytes());
				}
			}
			clientHelper.addLocalMessage(txn, m, meta, false, false);
			PrivateMessageHeader header = new PrivateMessageHeader(m.getId(),
					groupId, timestamp, false, false, false, false, true,
					emptyList(), NO_AUTO_DELETE_TIMER, replyToId, true);
			txn.attach(new PrivateMessageReceivedEvent(header, contactId));
			conversationManager.trackIncomingMessage(txn, m);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Nullable
	private MessageId resolveMeshParent(Transaction txn, GroupId groupId,
			byte[] canonicalId) throws DbException {
		try {
			Map<MessageId, BdfDictionary> all =
					clientHelper.getMessageMetadataAsDictionary(txn, groupId);
			for (Map.Entry<MessageId, BdfDictionary> e : all.entrySet()) {
				if (java.util.Arrays.equals(e.getKey().getBytes(),
						canonicalId)) {
					return e.getKey();
				}
				byte[] sid =
						e.getValue().getOptionalRaw(MSG_KEY_MESH_SENDER_ID);
				if (sid != null
						&& java.util.Arrays.equals(sid, canonicalId)) {
					return e.getKey();
				}
			}
		} catch (FormatException e) {
		}
		return null;
	}

	@Override
	public byte[] getMeshCanonicalId(MessageId localId) throws DbException {
		return db.transactionWithResult(true, txn -> {
			try {
				BdfDictionary meta = clientHelper
						.getMessageMetadataAsDictionary(txn, localId);
				byte[] sid = meta.getOptionalRaw(MSG_KEY_MESH_SENDER_ID);
				return sid != null ? sid : localId.getBytes();
			} catch (NoSuchMessageException | FormatException e) {
				return localId.getBytes();
			}
		});
	}

	@Override
	public void receiveMeshAttachment(ContactId contactId, String contentType,
			byte[] imageBytes, long timestamp) throws DbException {
		db.transaction(false, txn -> {
			try {
				GroupId groupId = getConversationId(txn, contactId);
				byte[] descriptor = clientHelper.toByteArray(
						BdfList.of(ATTACHMENT, contentType));
				byte[] body = new byte[descriptor.length + imageBytes.length];
				System.arraycopy(descriptor, 0, body, 0, descriptor.length);
				System.arraycopy(imageBytes, 0, body, descriptor.length,
						imageBytes.length);
				Message att =
						clientHelper.createMessage(groupId, timestamp, body);
				BdfDictionary attMeta = new BdfDictionary();
				attMeta.put(MSG_KEY_TIMESTAMP, timestamp);
				attMeta.put(MSG_KEY_LOCAL, false);
				attMeta.put(MSG_KEY_MSG_TYPE, ATTACHMENT);
				attMeta.put(MSG_KEY_CONTENT_TYPE, contentType);
				attMeta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptor.length);
				clientHelper.addLocalMessage(txn, att, attMeta, false, false);

				BdfList attachmentList = BdfList.of(
						BdfList.of(att.getId().getBytes(), contentType));
				BdfList pmBody =
						BdfList.of(PRIVATE_MESSAGE, null, attachmentList);
				Message pm =
						clientHelper.createMessage(groupId, timestamp, pmBody);
				BdfDictionary pmMeta = new BdfDictionary();
				pmMeta.put(MSG_KEY_TIMESTAMP, timestamp);
				pmMeta.put(MSG_KEY_LOCAL, false);
				pmMeta.put(MSG_KEY_READ, false);
				pmMeta.put(MSG_KEY_MESH, true);
				pmMeta.put(MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE);
				pmMeta.put(MSG_KEY_HAS_TEXT, false);
				pmMeta.put(MSG_KEY_ATTACHMENT_HEADERS, attachmentList);
				clientHelper.addLocalMessage(txn, pm, pmMeta, false, false);

				AttachmentHeader header = new AttachmentHeader(groupId,
						att.getId(), contentType);
				PrivateMessageHeader h = new PrivateMessageHeader(pm.getId(),
						groupId, timestamp, false, false, false, false, false,
						java.util.Collections.singletonList(header),
						NO_AUTO_DELETE_TIMER, null, true);
				txn.attach(new PrivateMessageReceivedEvent(h, contactId));
				conversationManager.trackIncomingMessage(txn, pm);
			} catch (FormatException e) {
				throw new DbException(e);
			}
		});
	}

	@Override
	public PrivateMessageHeader addLocalMeshAttachment(ContactId contactId,
			String contentType, byte[] imageBytes, long timestamp)
			throws DbException {
		return db.transactionWithResult(false, txn -> {
			try {
				GroupId groupId = getConversationId(txn, contactId);
				byte[] descriptor = clientHelper.toByteArray(
						BdfList.of(ATTACHMENT, contentType));
				byte[] body = new byte[descriptor.length + imageBytes.length];
				System.arraycopy(descriptor, 0, body, 0, descriptor.length);
				System.arraycopy(imageBytes, 0, body, descriptor.length,
						imageBytes.length);
				Message att =
						clientHelper.createMessage(groupId, timestamp, body);
				BdfDictionary attMeta = new BdfDictionary();
				attMeta.put(MSG_KEY_TIMESTAMP, timestamp);
				attMeta.put(MSG_KEY_LOCAL, true);
				attMeta.put(MSG_KEY_MSG_TYPE, ATTACHMENT);
				attMeta.put(MSG_KEY_CONTENT_TYPE, contentType);
				attMeta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptor.length);
				clientHelper.addLocalMessage(txn, att, attMeta, false, false);

				BdfList attachmentList = BdfList.of(
						BdfList.of(att.getId().getBytes(), contentType));
				BdfList pmBody =
						BdfList.of(PRIVATE_MESSAGE, null, attachmentList);
				Message pm =
						clientHelper.createMessage(groupId, timestamp, pmBody);
				BdfDictionary pmMeta = new BdfDictionary();
				pmMeta.put(MSG_KEY_TIMESTAMP, timestamp);
				pmMeta.put(MSG_KEY_LOCAL, true);
				pmMeta.put(MSG_KEY_READ, true);
				pmMeta.put(MSG_KEY_MESH, true);
				pmMeta.put(MSG_KEY_MSG_TYPE, PRIVATE_MESSAGE);
				pmMeta.put(MSG_KEY_HAS_TEXT, false);
				pmMeta.put(MSG_KEY_ATTACHMENT_HEADERS, attachmentList);
				pmMeta.put(MSG_KEY_MESH_STATE, MESH_STATE_SENT);
				clientHelper.addLocalMessage(txn, pm, pmMeta, false, false);

				AttachmentHeader header = new AttachmentHeader(groupId,
						att.getId(), contentType);
				conversationManager.trackOutgoingMessage(txn, pm);
				return new PrivateMessageHeader(pm.getId(), groupId, timestamp,
						true, true, false, false, false,
						java.util.Collections.singletonList(header),
						NO_AUTO_DELETE_TIMER, null, true);
			} catch (FormatException e) {
				throw new DbException(e);
			}
		});
	}

	@Override
	public void receiveMeshGroupRecord(ContactId contactId, byte[] record,
			long timestamp) throws DbException {
		db.transaction(false, txn -> {
			try {
				Contact c = db.getContact(txn, contactId);
				Group g = getContactGroup(c);
				Message m = clientHelper.createMessage(g.getId(), timestamp,
						record);
				try {
					clientHelper.getMessageMetadataAsDictionary(txn, m.getId());
					return;
				} catch (NoSuchMessageException expected) {
				}
				BdfDictionary meta;
				try {
					meta = privateMessageValidator.validateToBdf(m, g)
							.getDictionary();
					clientHelper.addLocalMessage(txn, m, meta, false, false);
					dispatchIncoming(txn, m, meta);
				} catch (InvalidMessageException e) {
				}
			} catch (FormatException e) {
				throw new DbException(e);
			}
		});
	}

	@Override
	public void setMeshMessageState(ContactId contactId, MessageId messageId,
			int state) throws DbException {
		db.transaction(false, txn -> {
			BdfDictionary meta = new BdfDictionary();
			meta.put(MSG_KEY_MESH_STATE, state);
			try {
				clientHelper.mergeMessageMetadata(txn, messageId, meta);
			} catch (FormatException e) {
				throw new DbException(e);
			}
			if (state >= MESH_STATE_DELIVERED) {
				txn.attach(new MessagesAckedEvent(contactId,
						singletonList(messageId)));
			} else if (state >= MESH_STATE_SENT) {
				txn.attach(new MessagesSentEvent(contactId,
						singletonList(messageId), 0));
			}
		});
	}

	@Override
	public java.util.List<UndeliveredMeshMessage> getUndeliveredMeshMessages()
			throws DbException {
		return db.transactionWithResult(true, txn -> {
			java.util.List<UndeliveredMeshMessage> result =
					new java.util.ArrayList<>();
			try {
				for (Contact contact : db.getContacts(txn)) {
					GroupId g = getContactGroup(contact).getId();
					Map<MessageId, BdfDictionary> messages =
							clientHelper.getMessageMetadataAsDictionary(txn, g);
					for (Map.Entry<MessageId, BdfDictionary> e :
							messages.entrySet()) {
						BdfDictionary meta = e.getValue();
						if (!meta.getBoolean(MSG_KEY_MESH, false)) continue;
						if (!meta.getBoolean(MSG_KEY_LOCAL, false)) continue;
						long state = meta.getLong(MSG_KEY_MESH_STATE,
								(long) MESH_STATE_PENDING);
						if (state >= MESH_STATE_DELIVERED) continue;
						String text = getMessageText(txn, e.getKey());
						long ts = meta.getLong(MSG_KEY_TIMESTAMP, 0L);
						byte[] replyTo =
								meta.getOptionalRaw(MSG_KEY_REPLY_TO_ID);
						result.add(new UndeliveredMeshMessage(contact.getId(),
								e.getKey(), text, ts, replyTo));
					}
				}
			} catch (FormatException ex) {
				throw new DbException(ex);
			}
			return result;
		});
	}

	@Override
	public java.util.List<UndeliveredMeshGroupRecord>
			getUndeliveredMeshGroupRecords() throws DbException {
		return db.transactionWithResult(true, txn -> {
			java.util.List<UndeliveredMeshGroupRecord> result =
					new java.util.ArrayList<>();
			try {
				for (Contact contact : db.getContacts(txn)) {
					GroupId g = getContactGroup(contact).getId();
					Map<MessageId, BdfDictionary> messages =
							clientHelper.getMessageMetadataAsDictionary(txn, g);
					for (Map.Entry<MessageId, BdfDictionary> e :
							messages.entrySet()) {
						BdfDictionary meta = e.getValue();
						if (!meta.getBoolean(MSG_KEY_MESH_GROUP_PENDING,
								false)) {
							continue;
						}
						Message m = clientHelper.getMessage(txn, e.getKey());
						if (m == null) continue;
						result.add(new UndeliveredMeshGroupRecord(
								contact.getId(), m.getBody(),
								m.getTimestamp()));
					}
				}
			} catch (FormatException ex) {
				throw new DbException(ex);
			}
			return result;
		});
	}

	@Override
	public void shareUndeliveredMeshGroupRecords() throws DbException {
		db.transaction(false, txn -> {
			try {
				for (Contact contact : db.getContacts(txn)) {
					GroupId g = getContactGroup(contact).getId();
					Map<MessageId, BdfDictionary> messages =
							clientHelper.getMessageMetadataAsDictionary(txn, g);
					for (Map.Entry<MessageId, BdfDictionary> e :
							messages.entrySet()) {
						if (!e.getValue().getBoolean(MSG_KEY_MESH_GROUP_PENDING,
								false)) {
							continue;
						}
						db.setMessageShared(txn, e.getKey());
						BdfDictionary update = new BdfDictionary();
						update.put(MSG_KEY_MESH_GROUP_PENDING, false);
						clientHelper.mergeMessageMetadata(txn, e.getKey(),
								update);
					}
				}
			} catch (FormatException ex) {
				throw new DbException(ex);
			}
		});
	}

	@Override
	public void sendPrekeyBundle(ContactId contactId, byte[] bundle)
			throws DbException {
		db.transaction(false, txn -> {
			try {
				Contact contact = db.getContact(txn, contactId);
				GroupId groupId = getContactGroup(contact).getId();
				long timestamp = System.currentTimeMillis();
				BdfList body = BdfList.of(
						MessageTypes.MESH_PREKEY_BUNDLE, bundle);
				Message m = clientHelper.createMessage(groupId, timestamp, body);
				BdfDictionary meta = new BdfDictionary();
				meta.put(MSG_KEY_TIMESTAMP, timestamp);
				meta.put(MSG_KEY_LOCAL, true);
				meta.put(MSG_KEY_READ, true);
				meta.put(MSG_KEY_MSG_TYPE, MessageTypes.MESH_PREKEY_BUNDLE);
				clientHelper.addLocalMessage(txn, m, meta, true, false);
				db.setCleanupTimerDuration(txn, m.getId(),
						7L * 24 * 3600 * 1000);
				db.startCleanupTimer(txn, m.getId());
			} catch (FormatException e) {
				throw new DbException(e);
			}
		});
	}

	private void incomingPrekeyBundle(Transaction txn, Message m)
			throws DbException, FormatException {
		ContactId contactId = getContactId(txn, m.getGroupId());
		BdfList body = clientHelper.toList(m.getBody());
		byte[] bundle = body.getRaw(1);
		txn.attach(new PrekeyBundleReceivedEvent(contactId, bundle));
	}

	private void shareAttachmentChunks(Transaction txn, MessageId attachmentId)
			throws DbException, FormatException {
		BdfDictionary attachmentMeta =
				clientHelper.getMessageMetadataAsDictionary(txn, attachmentId);
		Integer msgType = attachmentMeta.getOptionalInt(MSG_KEY_MSG_TYPE);
		if (msgType == null || msgType != ATTACHMENT_MANIFEST) {
			return;
		}
		Message manifestMessage = clientHelper.getMessage(txn, attachmentId);
		BdfList manifestBody = clientHelper.toList(manifestMessage.getBody());
		BdfList chunkIdList = manifestBody.getList(5);
		for (int i = 0; i < chunkIdList.size(); i++) {
			byte[] chunkIdBytes = chunkIdList.getRaw(i);
			MessageId chunkId = new MessageId(chunkIdBytes);
			db.setMessageShared(txn, chunkId);
			db.setMessagePermanent(txn, chunkId);
		}
	}

	@Override
	public void addLocalVoiceSignal(VoiceSignal signal) throws DbException {
		db.transaction(false, txn -> {
			try {
				BdfDictionary meta = new BdfDictionary();
				meta.put(MSG_KEY_TIMESTAMP, signal.getMessage().getTimestamp());
				meta.put(MSG_KEY_LOCAL, true);
				meta.put(MSG_KEY_READ, true);
				meta.put(MSG_KEY_MSG_TYPE, MessageTypes.VOICE_SIGNAL);
				clientHelper.addLocalMessage(txn, signal.getMessage(), meta, true,
						false);
				conversationManager.trackOutgoingMessage(txn, signal.getMessage());
			} catch (FormatException e) {
				throw new AssertionError(e);
			}
		});
	}

	@Override
	public AttachmentHeader addLocalAttachment(GroupId groupId, long timestamp,
			String contentType, InputStream in)
			throws DbException, IOException {
		ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
		byte[] descriptor =
				clientHelper.toByteArray(BdfList.of(ATTACHMENT, contentType));
		bodyOut.write(descriptor);
		copyAndClose(in, bodyOut);
		if (bodyOut.size() > MAX_MESSAGE_BODY_LENGTH)
			throw new FileTooBigException();
		byte[] body = bodyOut.toByteArray();
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_TIMESTAMP, timestamp);
		meta.put(MSG_KEY_LOCAL, true);
		meta.put(MSG_KEY_MSG_TYPE, ATTACHMENT);
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		meta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptor.length);
		Message m = clientHelper.createMessage(groupId, timestamp, body);
		db.transaction(false, txn ->
				clientHelper.addLocalMessage(txn, m, meta, false, true));
		return new AttachmentHeader(groupId, m.getId(), contentType);
	}

	@Override
	public AttachmentHeader addLocalAttachmentStreaming(GroupId groupId,
			long timestamp, String contentType, InputStream is, long totalSize,
			ProgressCallback progressCallback) throws DbException, IOException {
		return streamingAttachmentWriter.storeAttachment(groupId, timestamp,
				contentType, is, totalSize, progressCallback);
	}

	@Override
	public void removeAttachment(AttachmentHeader header) throws DbException {
		db.transaction(false,
				txn -> db.removeMessage(txn, header.getMessageId()));
	}

	private ContactId getContactId(Transaction txn, GroupId g)
			throws DbException {
		try {
			BdfDictionary meta =
					clientHelper.getGroupMetadataAsDictionary(txn, g);
			return new ContactId(meta.getInt(GROUP_KEY_CONTACT_ID));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public ContactId getContactId(GroupId g) throws DbException {
		try {
			BdfDictionary meta = clientHelper.getGroupMetadataAsDictionary(g);
			return new ContactId(meta.getInt(GROUP_KEY_CONTACT_ID));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public GroupId getConversationId(ContactId c) throws DbException {
		return db.transactionWithResult(true,
				txn -> getConversationId(txn, c));
	}

	@Override
	public GroupId getConversationId(Transaction txn, ContactId c) throws DbException {
		Contact contact = db.getContact(txn, c);
		return getContactGroup(contact).getId();
	}

	@Override
	public Collection<ConversationMessageHeader> getMessageHeaders(
			Transaction txn, ContactId c) throws DbException {
		Map<MessageId, BdfDictionary> metadata;
		Collection<MessageStatus> statuses;
		GroupId g;
		try {
			g = getContactGroup(db.getContact(txn, c)).getId();
			metadata = clientHelper.getMessageMetadataAsDictionary(txn, g);
			statuses = db.getMessageStatus(txn, c, g);
		} catch (FormatException e) {
			throw new DbException(e);
		}
		Collection<ConversationMessageHeader> headers = new ArrayList<>();
		for (MessageStatus s : statuses) {
			MessageId id = s.getMessageId();
			BdfDictionary meta = metadata.get(id);
			if (meta == null) continue;
			try {
				Integer messageType = meta.getOptionalInt(MSG_KEY_MSG_TYPE);
				if (messageType != null
						&& messageType == MessageTypes.GROUPTR_INVITE_OFFER) {
					if (meta.getBoolean(MSG_KEY_LOCAL, false)) continue;
					byte[] grouptrGidRaw = meta.getOptionalRaw(
							MessagingConstants.MSG_KEY_GROUP_ID);
					if (grouptrGidRaw == null) continue;
					String groupName = meta.getOptionalString(
							MessagingConstants.MSG_KEY_GTR_INVITE_NAME);
					byte[] salt = meta.getOptionalRaw(
							MessagingConstants.MSG_KEY_GTR_INVITE_SALT);
					String creatorName = meta.getOptionalString(
							MessagingConstants
									.MSG_KEY_GTR_INVITE_CREATOR_NAME);
					byte[] creatorPub = meta.getOptionalRaw(
							MessagingConstants
									.MSG_KEY_GTR_INVITE_CREATOR_PUB);
					long inviteTs = meta.getLong("gtrInviteTimestamp");
					long ts = meta.getLong(MSG_KEY_TIMESTAMP);
					boolean read = meta.getBoolean(MSG_KEY_READ, false);
					if (salt == null || creatorPub == null
							|| creatorName == null || groupName == null) {
						continue;
					}
					headers.add(new org.zerionproject.app.api.grouptr
							.GroupTrInvitationHeader(id, g, ts, false, read,
							s.isSent(), s.isSeen(),
							new GroupId(grouptrGidRaw), groupName, salt,
							creatorName, creatorPub, inviteTs));
					continue;
				}
				if (messageType != null && messageType != PRIVATE_MESSAGE
						&& messageType != MessageTypes.LINK_PREVIEW_MESSAGE)
					continue;
				Long timestampOpt = meta.getOptionalLong(MSG_KEY_TIMESTAMP);
				if (timestampOpt == null) continue;
				long timestamp = timestampOpt;
				boolean local = meta.getBoolean(MSG_KEY_LOCAL);
				boolean read = meta.getBoolean(MSG_KEY_READ);
				boolean mesh = meta.getBoolean(MSG_KEY_MESH, false);
				long meshState = mesh ?
						meta.getLong(MSG_KEY_MESH_STATE,
								(long) MESH_STATE_PENDING) : 0;
				boolean sent = mesh ? meshState >= MESH_STATE_SENT : s.isSent();
				boolean seen = mesh ? meshState >= MESH_STATE_DELIVERED
						: s.isSeen();
				if (messageType == null) {
					headers.add(new PrivateMessageHeader(id, g, timestamp,
							local, read, sent, seen, true,
							emptyList(), NO_AUTO_DELETE_TIMER, null, mesh));
				} else {
					boolean hasText = meta.getBoolean(MSG_KEY_HAS_TEXT);
					long timer = meta.getLong(MSG_KEY_AUTO_DELETE_TIMER,
							NO_AUTO_DELETE_TIMER);
					byte[] replyToIdBytes =
							meta.getOptionalRaw(MSG_KEY_REPLY_TO_ID);
					MessageId replyToId = replyToIdBytes != null ?
							new MessageId(replyToIdBytes) : null;
					headers.add(new PrivateMessageHeader(id, g, timestamp,
							local, read, sent, seen, hasText,
							parseAttachmentHeaders(g, meta), timer,
							replyToId, mesh));
				}
			} catch (FormatException e) {
				throw new DbException(e);
			}
		}
		return headers;
	}

	@Override
	public Set<MessageId> getMessageIds(Transaction txn, ContactId c)
			throws DbException {
		GroupId g = getContactGroup(db.getContact(txn, c)).getId();
		Set<MessageId> result = new HashSet<>();
		try {
			Map<MessageId, BdfDictionary> messages =
					clientHelper.getMessageMetadataAsDictionary(txn, g);
			for (Entry<MessageId, BdfDictionary> entry : messages.entrySet()) {
				Integer type =
						entry.getValue().getOptionalInt(MSG_KEY_MSG_TYPE);
				if (type == null || type == PRIVATE_MESSAGE
						|| type == MessageTypes.LINK_PREVIEW_MESSAGE)
					result.add(entry.getKey());
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
		return result;
	}

	@Override
	public String getMessageText(MessageId m) throws DbException {
		return db.transactionWithNullableResult(true, txn ->
				getMessageText(txn, m));
	}

	@Override
	public String getMessageText(Transaction txn, MessageId m) throws DbException {
		try {
			BdfList body = clientHelper.getMessageAsList(txn, m);
			if (body.size() == 1) return body.getString(0);
			else return body.getOptionalString(1);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public Map<MessageId, String> getMessageTexts(ContactId c) throws DbException {
		return db.transactionWithResult(true, txn -> getMessageTexts(txn, c));
	}

	@Override
	public Map<MessageId, String> getMessageTexts(Transaction txn, ContactId c)
			throws DbException {
		Map<MessageId, String> texts = new java.util.HashMap<>();
		try {
			GroupId g = getContactGroup(db.getContact(txn, c)).getId();
			Map<MessageId, BdfDictionary> metadata =
					clientHelper.getMessageMetadataAsDictionary(txn, g);
			for (Entry<MessageId, BdfDictionary> entry : metadata.entrySet()) {
				MessageId id = entry.getKey();
				BdfDictionary meta = entry.getValue();
				Integer messageType = meta.getOptionalInt(MSG_KEY_MSG_TYPE);
				if (messageType != null && messageType != PRIVATE_MESSAGE
						&& messageType != MessageTypes.LINK_PREVIEW_MESSAGE)
					continue;
				boolean hasText = messageType == null ||
						meta.getBoolean(MSG_KEY_HAS_TEXT, false);
				if (!hasText) continue;
				try {
					BdfList body = clientHelper.getMessageAsList(txn, id);
					String text;
					if (body.size() == 1) text = body.getString(0);
					else text = body.getOptionalString(1);
					if (text != null) texts.put(id, text);
				} catch (FormatException e) {
				} catch (
						org.zerionproject.core.api.db.NoSuchMessageException e) {

				}
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
		return texts;
	}

	@Override
	public PrivateMessageFormat getContactMessageFormat(Transaction txn,
			ContactId c) throws DbException {
		int minorVersion = clientVersioningManager
				.getClientMinorVersion(txn, c, CLIENT_ID, 0);
		if (minorVersion >= 4) return TEXT_IMAGES_CHUNKED;
		else if (minorVersion >= 3) return TEXT_IMAGES_AUTO_DELETE;
		else if (minorVersion >= 1) return TEXT_IMAGES;
		else return TEXT_ONLY;
	}

	@Override
	public DeletionResult deleteAllMessages(Transaction txn, ContactId c)
			throws DbException {
		GroupId g = getContactGroup(db.getContact(txn, c)).getId();

		db.removeAllGroupMessages(txn, g);
		messageTracker.initializeGroupCount(txn, g);
		return new DeletionResult();
	}

	@Override
	public DeletionResult deleteMessages(Transaction txn, ContactId c,
			Set<MessageId> messageIds) throws DbException {
		GroupId g = getContactGroup(db.getContact(txn, c)).getId();
		for (MessageId m : messageIds) deleteMessage(txn, g, m);
		recalculateGroupCount(txn, g);
		return new DeletionResult();
	}

	@Override
	public void deleteMessages(Transaction txn, GroupId g,
			Collection<MessageId> messageIds) throws DbException {
		for (MessageId m : messageIds) deleteMessage(txn, g, m);
		recalculateGroupCount(txn, g);
		ContactId c = getContactId(txn, g);
		txn.attach(new ConversationMessagesDeletedEvent(c, messageIds));
	}

	private void deleteMessage(Transaction txn, GroupId g, MessageId m)
			throws DbException {
		try {
			BdfDictionary meta =
					clientHelper.getMessageMetadataAsDictionary(txn, m);
			Integer messageType = meta.getOptionalInt(MSG_KEY_MSG_TYPE);
			if (messageType != null && messageType == PRIVATE_MESSAGE) {
				boolean local = meta.getBoolean(MSG_KEY_LOCAL, false);
				for (AttachmentHeader h : parseAttachmentHeaders(g, meta)) {
					try {
						removeAttachmentMessage(txn, h.getMessageId(), local);
					} catch (NoSuchMessageException e) {
					}
				}
			}
			db.removeMessage(txn, m);
		} catch (NoSuchMessageException e) {

		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void removeAttachmentMessage(Transaction txn, MessageId id,
			boolean local) throws DbException, FormatException {
		BdfDictionary meta =
				clientHelper.getMessageMetadataAsDictionary(txn, id);
		Integer type = meta.getOptionalInt(MSG_KEY_MSG_TYPE);
		if (type == null) return;
		if (type != ATTACHMENT && type != ATTACHMENT_MANIFEST) return;
		if (meta.getBoolean(MSG_KEY_LOCAL, false) != local) return;
		if (type == ATTACHMENT_MANIFEST) {
			try {
				Message manifest = clientHelper.getMessage(txn, id);
				BdfList chunkIds =
						clientHelper.toList(manifest.getBody()).getList(5);
				for (int i = 0; i < chunkIds.size(); i++) {
					try {
						db.removeMessage(txn,
								new MessageId(chunkIds.getRaw(i)));
					} catch (NoSuchMessageException e) {
					}
				}
			} catch (NoSuchMessageException e) {
			}
		}
		db.removeMessage(txn, id);
	}

	private void recalculateGroupCount(Transaction txn, GroupId g)
			throws DbException {
		try {
			Map<MessageId, BdfDictionary> metadata =
					clientHelper.getMessageMetadataAsDictionary(txn, g);
			int msgCount = 0;
			int unreadCount = 0;
			for (Entry<MessageId, BdfDictionary> entry : metadata.entrySet()) {
				BdfDictionary meta = entry.getValue();
				Integer messageType = meta.getOptionalInt(MSG_KEY_MSG_TYPE);
				if (messageType == null || messageType == PRIVATE_MESSAGE
						|| messageType == MessageTypes.LINK_PREVIEW_MESSAGE) {
					msgCount++;
					if (!meta.getBoolean(MSG_KEY_READ)) unreadCount++;
				}
			}
			messageTracker.resetGroupCount(txn, g, msgCount, unreadCount);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void incomingReaction(Transaction txn, Message m,
			BdfDictionary meta) throws DbException, FormatException {
		GroupId groupId = m.getGroupId();
		byte[] targetIdBytes = meta.getRaw(MSG_KEY_TARGET_MESSAGE_ID);
		MessageId targetId = new MessageId(targetIdBytes);
		String emoji = meta.getString(MSG_KEY_REACTION_EMOJI);
		ContactId contactId = getContactId(txn, groupId);

		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(MSG_KEY_MSG_TYPE,
						MessageTypes.MESSAGE_REACTION));
		Map<MessageId, BdfDictionary> existing =
				clientHelper.getMessageMetadataAsDictionary(
						txn, groupId, query);
		for (Entry<MessageId, BdfDictionary> entry :
				existing.entrySet()) {
			BdfDictionary eMeta = entry.getValue();
			if (eMeta.getBoolean(MSG_KEY_LOCAL)) continue;
			byte[] eTarget = eMeta.getRaw(MSG_KEY_TARGET_MESSAGE_ID);
			String eEmoji = eMeta.getString(MSG_KEY_REACTION_EMOJI);
			if (java.util.Arrays.equals(eTarget, targetIdBytes) &&
					emoji.equals(eEmoji)) {

				db.removeMessage(txn, entry.getKey());
				break;
			}
		}

		ReactionReceivedEvent event = new ReactionReceivedEvent(
				contactId, targetId, emoji, false);
		txn.attach(event);
	}

	private void incomingTypingIndicator(Transaction txn, Message m,
			BdfDictionary meta) throws DbException, FormatException {
		GroupId groupId = m.getGroupId();
		boolean isTyping = meta.getBoolean(MSG_KEY_IS_TYPING);
		ContactId contactId = getContactId(txn, groupId);
		TypingIndicatorReceivedEvent event =
				new TypingIndicatorReceivedEvent(contactId, isTyping);
		txn.attach(event);

	}

	private void incomingLinkPreviewMessage(Transaction txn, Message m,
			BdfDictionary meta) throws DbException, FormatException {
		GroupId groupId = m.getGroupId();
		long timestamp = meta.getLong(MSG_KEY_TIMESTAMP);
		boolean local = meta.getBoolean(MSG_KEY_LOCAL);
		boolean read = meta.getBoolean(MSG_KEY_READ);
		boolean hasText = meta.getBoolean(MSG_KEY_HAS_TEXT);
		PrivateMessageHeader header = new PrivateMessageHeader(
				m.getId(), groupId, timestamp, local, read,
				false, false, hasText, java.util.Collections.emptyList(),
				NO_AUTO_DELETE_TIMER);
		ContactId contactId = getContactId(txn, groupId);
		PrivateMessageReceivedEvent event =
				new PrivateMessageReceivedEvent(header, contactId);
		txn.attach(event);
		conversationManager.trackIncomingMessage(txn, m);
	}

	private boolean peerSupportsExtendedMessages(Transaction txn,
			ContactId contactId) throws DbException {

		return true;
	}

	@Override
	public void addLocalReaction(ContactId contactId,
			MessageId targetMessageId, String emoji) throws DbException {
		db.transaction(false, txn -> {
			if (!peerSupportsExtendedMessages(txn, contactId)) return;
			try {
				Contact contact = db.getContact(txn, contactId);
				GroupId groupId = getContactGroup(contact).getId();

				BdfDictionary query = BdfDictionary.of(
						new BdfEntry(MSG_KEY_MSG_TYPE,
								MessageTypes.MESSAGE_REACTION));
				Map<MessageId, BdfDictionary> existing =
						clientHelper.getMessageMetadataAsDictionary(
								txn, groupId, query);
				for (Entry<MessageId, BdfDictionary> entry :
						existing.entrySet()) {
					BdfDictionary eMeta = entry.getValue();
					if (!eMeta.getBoolean(MSG_KEY_LOCAL)) continue;
					byte[] eTarget =
							eMeta.getRaw(MSG_KEY_TARGET_MESSAGE_ID);
					String eEmoji =
							eMeta.getString(MSG_KEY_REACTION_EMOJI);
					if (java.util.Arrays.equals(eTarget,
							targetMessageId.getBytes()) &&
							emoji.equals(eEmoji)) {

						db.removeMessage(txn, entry.getKey());
						ReactionReceivedEvent event =
								new ReactionReceivedEvent(contactId,
										targetMessageId, emoji, true);
						txn.attach(event);
						return;
					}
				}

				long timestamp = System.currentTimeMillis();
				BdfList body = BdfList.of(MessageTypes.MESSAGE_REACTION,
						targetMessageId.getBytes(), emoji);
				Message m = clientHelper.createMessage(
						groupId, timestamp, body);
				BdfDictionary meta = new BdfDictionary();
				meta.put(MSG_KEY_TIMESTAMP, timestamp);
				meta.put(MSG_KEY_LOCAL, true);
				meta.put(MSG_KEY_READ, true);
				meta.put(MSG_KEY_MSG_TYPE, MessageTypes.MESSAGE_REACTION);
				meta.put(MSG_KEY_TARGET_MESSAGE_ID,
						targetMessageId.getBytes());
				meta.put(MSG_KEY_REACTION_EMOJI, emoji);
				clientHelper.addLocalMessage(txn, m, meta, true, false);
				ReactionReceivedEvent event = new ReactionReceivedEvent(
						contactId, targetMessageId, emoji, true);
				txn.attach(event);
			} catch (FormatException e) {
				throw new DbException(e);
			}
		});
	}

	@Override
	public Map<MessageId, Map<String, Integer>> getReactions(ContactId c)
			throws DbException {
		return db.transactionWithResult(true, txn -> {
			try {
				Contact contact = db.getContact(txn, c);
				GroupId g = getContactGroup(contact).getId();
				BdfDictionary query = BdfDictionary.of(
						new BdfEntry(MSG_KEY_MSG_TYPE,
								MessageTypes.MESSAGE_REACTION));
				Map<MessageId, BdfDictionary> results =
						clientHelper.getMessageMetadataAsDictionary(
								txn, g, query);
				Map<MessageId, Map<String, Integer>> reactions =
						new java.util.HashMap<>();
				for (BdfDictionary meta : results.values()) {
					byte[] targetIdBytes =
							meta.getRaw(MSG_KEY_TARGET_MESSAGE_ID);
					MessageId targetId = new MessageId(targetIdBytes);
					String emoji = meta.getString(MSG_KEY_REACTION_EMOJI);
					Map<String, Integer> msgReactions = reactions.get(targetId);
					if (msgReactions == null) {
						msgReactions = new java.util.HashMap<>();
						reactions.put(targetId, msgReactions);
					}
					msgReactions.merge(emoji, 1, Integer::sum);
				}
				return reactions;
			} catch (FormatException e) {
				throw new DbException(e);
			}
		});
	}

	@Override
	public void sendTypingIndicator(ContactId contactId, boolean isTyping)
			throws DbException {
		db.transaction(false, txn -> {
			if (!peerSupportsExtendedMessages(txn, contactId)) return;
			try {
				Contact contact = db.getContact(txn, contactId);
				GroupId groupId = getContactGroup(contact).getId();
				long timestamp = System.currentTimeMillis();
				BdfList body = BdfList.of(
						MessageTypes.TYPING_INDICATOR, isTyping);
				Message m = clientHelper.createMessage(
						groupId, timestamp, body);
				BdfDictionary meta = new BdfDictionary();
				meta.put(MSG_KEY_TIMESTAMP, timestamp);
				meta.put(MSG_KEY_LOCAL, true);
				meta.put(MSG_KEY_READ, true);
				meta.put(MSG_KEY_MSG_TYPE, MessageTypes.TYPING_INDICATOR);
				meta.put(MSG_KEY_IS_TYPING, isTyping);
				clientHelper.addLocalMessage(txn, m, meta, true, false);
				db.setCleanupTimerDuration(txn, m.getId(), 30000);
				db.startCleanupTimer(txn, m.getId());
			} catch (FormatException e) {
				throw new DbException(e);
			}
		});
	}

	@Override
	public java.util.Map<MessageId, LinkPreview> getLinkPreviews(
			ContactId c) throws DbException {
		return db.transactionWithResult(true, txn -> {
			try {
				Contact contact = db.getContact(txn, c);
				GroupId g = getContactGroup(contact).getId();
				BdfDictionary query = BdfDictionary.of(
						new BdfEntry(MSG_KEY_MSG_TYPE,
								MessageTypes.LINK_PREVIEW_MESSAGE));
				Map<MessageId, BdfDictionary> results =
						clientHelper.getMessageMetadataAsDictionary(
								txn, g, query);
				java.util.Map<MessageId, LinkPreview> previews =
						new java.util.HashMap<>();
				for (Entry<MessageId, BdfDictionary> entry :
						results.entrySet()) {
					BdfDictionary meta = entry.getValue();
					String url = meta.getOptionalString(MSG_KEY_PREVIEW_URL);
					String title = meta.getOptionalString(
							MSG_KEY_PREVIEW_TITLE);
					if (url == null || title == null) continue;
					String description = meta.getOptionalString(
							MSG_KEY_PREVIEW_DESCRIPTION);
					byte[] imageData = null;
					boolean hasImage = meta.getBoolean(
							MSG_KEY_HAS_PREVIEW_IMAGE, false);
					if (hasImage) {
						try {
							BdfList body = clientHelper
									.getMessageAsList(txn,
											entry.getKey());
							if (body.size() >= 6) {
								imageData = body.getOptionalRaw(5);
							}
						} catch (FormatException ignored) {
						} catch (
								org.zerionproject.core.api.db.NoSuchMessageException ignored) {
						}
					}
					previews.put(entry.getKey(),
							new LinkPreview(url, title, description,
									imageData));
				}
				return previews;
			} catch (FormatException e) {
				throw new DbException(e);
			}
		});
	}

	@Override
	public void addLocalLinkPreviewMessage(Transaction txn,
			ContactId contactId, @javax.annotation.Nullable String text,
			LinkPreview preview) throws DbException {
		try {
			Contact contact = db.getContact(txn, contactId);
			GroupId groupId = getContactGroup(contact).getId();
			long timestamp = System.currentTimeMillis();
			BdfList body;
			if (preview.hasImage()) {
				body = BdfList.of(
						MessageTypes.LINK_PREVIEW_MESSAGE,
						text, preview.getUrl(), preview.getTitle(),
						preview.getDescription(), preview.getImageData());
			} else {
				body = BdfList.of(
						MessageTypes.LINK_PREVIEW_MESSAGE,
						text, preview.getUrl(), preview.getTitle(),
						preview.getDescription());
			}
			Message m = clientHelper.createMessage(groupId, timestamp, body);
			BdfDictionary meta = new BdfDictionary();
			meta.put(MSG_KEY_TIMESTAMP, timestamp);
			meta.put(MSG_KEY_LOCAL, true);
			meta.put(MSG_KEY_READ, true);
			meta.put(MSG_KEY_MSG_TYPE, MessageTypes.LINK_PREVIEW_MESSAGE);
			meta.put(MSG_KEY_HAS_TEXT, text != null);
			meta.put(MSG_KEY_PREVIEW_URL, preview.getUrl());
			meta.put(MSG_KEY_PREVIEW_TITLE, preview.getTitle());
			if (preview.getDescription() != null) {
				meta.put(MSG_KEY_PREVIEW_DESCRIPTION,
						preview.getDescription());
			}
			meta.put(MSG_KEY_HAS_PREVIEW_IMAGE, preview.hasImage());
			clientHelper.addLocalMessage(txn, m, meta, true, false);
			PrivateMessageHeader header = new PrivateMessageHeader(
					m.getId(), groupId, timestamp, true, true,
					false, false, text != null,
					java.util.Collections.emptyList(),
					NO_AUTO_DELETE_TIMER);
			PrivateMessageReceivedEvent event =
					new PrivateMessageReceivedEvent(header, contactId);
			txn.attach(event);
			conversationManager.trackOutgoingMessage(txn, m);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void incomingGroupPost(Transaction txn, Message m,
			BdfDictionary meta) throws DbException, FormatException {
		ContactId contactId = getContactId(txn, m.getGroupId());
		byte[] groupId = meta.getRaw(MessagingConstants.MSG_KEY_GROUP_ID);
		long epoch = meta.getLong(MessagingConstants.MSG_KEY_GROUP_EPOCH);
		byte[] senderPubKey = meta.getRaw(
				MessagingConstants.MSG_KEY_GROUP_SENDER_PUBKEY);
		byte[] ciphertext = meta.getRaw(
				MessagingConstants.MSG_KEY_GROUP_CIPHERTEXT);
		long timestamp = meta.getLong(MSG_KEY_TIMESTAMP);
		long ttl = meta.getLong(MSG_KEY_AUTO_DELETE_TIMER,
				NO_AUTO_DELETE_TIMER);
		if (ttl != NO_AUTO_DELETE_TIMER) {
			db.setCleanupTimerDuration(txn, m.getId(), ttl);
		}
		String senderName = meta.getOptionalString("groupSenderName");
		if (senderName == null) senderName = "";
		byte[] recordSig = meta.getOptionalRaw(
				MessagingConstants.MSG_KEY_GROUP_RECORD_SIG);
		if (recordSig == null) recordSig = new byte[0];
		txn.attach(new org.zerionproject.app.api.messaging.event
				.GroupPostReceivedEvent(contactId, m.getId(), groupId,
				epoch, senderPubKey, senderName, ciphertext, timestamp,
				ttl == NO_AUTO_DELETE_TIMER ? 0L : ttl, recordSig));
	}

	private void incomingGroupMembership(Transaction txn, Message m,
			BdfDictionary meta, int messageType)
			throws DbException, FormatException {
		ContactId contactId = getContactId(txn, m.getGroupId());
		byte[] groupId = meta.getRaw(MessagingConstants.MSG_KEY_GROUP_ID);
		long timestamp = meta.getLong(MSG_KEY_TIMESTAMP);
		byte[] recordSig = meta.getRaw(
				MessagingConstants.MSG_KEY_GROUP_RECORD_SIG);
		byte[] signedInput = meta.getOptionalRaw(
				"groupMembershipSignedInput");
		if (signedInput == null) signedInput = new byte[0];
		org.zerionproject.app.api.messaging.event
				.GroupMembershipChangedEvent.ChangeKind kind;
		long epoch = 0L;
		long fromEpoch = 0L;
		long toEpoch = 0L;
		byte[] targetPubKey = null;
		String targetName = null;
		if (messageType == MessageTypes.GROUP_MEMBER_ADDED) {
			kind = org.zerionproject.app.api.messaging.event
					.GroupMembershipChangedEvent.ChangeKind.MEMBER_ADDED;
			epoch = meta.getLong(MessagingConstants.MSG_KEY_GROUP_EPOCH);
			targetPubKey = meta.getRaw(
					MessagingConstants.MSG_KEY_GROUP_ADDED_PUBKEY);
			targetName = meta.getString(
					MessagingConstants.MSG_KEY_GROUP_ADDED_NAME);
		} else if (messageType == MessageTypes.GROUP_MEMBER_REMOVED) {
			kind = org.zerionproject.app.api.messaging.event
					.GroupMembershipChangedEvent.ChangeKind.MEMBER_REMOVED;
			fromEpoch = meta.getLong(
					MessagingConstants.MSG_KEY_GROUP_FROM_EPOCH);
			toEpoch = meta.getLong(
					MessagingConstants.MSG_KEY_GROUP_TO_EPOCH);
			epoch = toEpoch;
			targetPubKey = meta.getRaw(
					MessagingConstants.MSG_KEY_GROUP_REMOVED_PUBKEY);
		} else if (messageType == MessageTypes.GROUP_MEMBER_LEFT) {
			kind = org.zerionproject.app.api.messaging.event
					.GroupMembershipChangedEvent.ChangeKind.MEMBER_LEFT;
			epoch = meta.getLong(MessagingConstants.MSG_KEY_GROUP_EPOCH);
			targetPubKey = meta.getRaw(
					MessagingConstants.MSG_KEY_GROUP_LEAVING_PUBKEY);
		} else if (messageType == MessageTypes.GROUP_DISSOLVED) {
			kind = org.zerionproject.app.api.messaging.event
					.GroupMembershipChangedEvent.ChangeKind.GROUP_DISSOLVED;
			epoch = meta.getLong(MessagingConstants.MSG_KEY_GROUP_EPOCH);
		} else {
			kind = org.zerionproject.app.api.messaging.event
					.GroupMembershipChangedEvent.ChangeKind.ROLE_CHANGED;
			epoch = meta.getLong(MessagingConstants.MSG_KEY_GROUP_EPOCH);
			targetPubKey = meta.getRaw(
					MessagingConstants.MSG_KEY_GROUP_TARGET_PUBKEY);
			int newRoleInt = meta.getLong(
					MessagingConstants.MSG_KEY_GROUP_NEW_ROLE).intValue();
			txn.attach(new org.zerionproject.app.api.messaging.event
					.GroupMembershipChangedEvent(contactId, kind, groupId,
					epoch, timestamp, targetPubKey, targetName,
					fromEpoch, toEpoch, recordSig, signedInput,
					newRoleInt));
			return;
		}
		txn.attach(new org.zerionproject.app.api.messaging.event
				.GroupMembershipChangedEvent(contactId, kind, groupId,
				epoch, timestamp, targetPubKey, targetName,
				fromEpoch, toEpoch, recordSig, signedInput));
	}

	private void incomingGroupEpochCommit(Transaction txn, Message m,
			BdfDictionary meta) throws DbException, FormatException {
		ContactId contactId = getContactId(txn, m.getGroupId());
		byte[] groupId = meta.getRaw(MessagingConstants.MSG_KEY_GROUP_ID);
		long fromEpoch = meta.getLong(
				MessagingConstants.MSG_KEY_GROUP_FROM_EPOCH);
		long toEpoch = meta.getLong(
				MessagingConstants.MSG_KEY_GROUP_TO_EPOCH);
		byte[] pqSeed = meta.getRaw(
				MessagingConstants.MSG_KEY_GROUP_PQ_SEED);
		byte[] recordSig = meta.getRaw(
				MessagingConstants.MSG_KEY_GROUP_RECORD_SIG);
		byte[] signedInput = meta.getOptionalRaw(
				"groupEpochCommitSignedInput");
		if (signedInput == null) signedInput = new byte[0];
		long timestamp = meta.getLong(MSG_KEY_TIMESTAMP);
		txn.attach(new org.zerionproject.app.api.messaging.event
				.GroupEpochCommitEvent(contactId, groupId, fromEpoch,
				toEpoch, pqSeed, recordSig, signedInput, timestamp));
	}

	private void incomingGroupMemberListSnapshot(Transaction txn, Message m,
			BdfDictionary meta) throws DbException, FormatException {
		ContactId contactId = getContactId(txn, m.getGroupId());
		byte[] groupId = meta.getRaw(MessagingConstants.MSG_KEY_GROUP_ID);
		long epoch = meta.getLong(MessagingConstants.MSG_KEY_GROUP_EPOCH);
		byte[] memberCanonical = meta.getRaw(
				MessagingConstants.MSG_KEY_GROUP_MEMBER_LIST);
		byte[] recordSig = meta.getRaw(
				MessagingConstants.MSG_KEY_GROUP_RECORD_SIG);
		byte[] signedInput = meta.getOptionalRaw(
				"groupMembershipSignedInput");
		if (signedInput == null) signedInput = new byte[0];
		long timestamp = meta.getLong(MSG_KEY_TIMESTAMP);
		txn.attach(new org.zerionproject.app.api.messaging.event
				.GroupMemberListSnapshotEvent(contactId, groupId, epoch,
				timestamp, memberCanonical, recordSig, signedInput));
	}

	private void incomingGrouptrInviteOffer(Transaction txn, Message m,
			BdfDictionary meta) throws DbException, FormatException {
		ContactId contactId = getContactId(txn, m.getGroupId());
		byte[] grouptrGid = meta.getRaw(MessagingConstants.MSG_KEY_GROUP_ID);
		String groupName = meta.getString(
				MessagingConstants.MSG_KEY_GTR_INVITE_NAME);
		byte[] salt = meta.getRaw(
				MessagingConstants.MSG_KEY_GTR_INVITE_SALT);
		String creatorName = meta.getString(
				MessagingConstants.MSG_KEY_GTR_INVITE_CREATOR_NAME);
		byte[] creatorPub = meta.getRaw(
				MessagingConstants.MSG_KEY_GTR_INVITE_CREATOR_PUB);
		long inviteTs = meta.getLong("gtrInviteTimestamp");
		byte[] recordSig = meta.getRaw(
				MessagingConstants.MSG_KEY_GROUP_RECORD_SIG);
		conversationManager.trackIncomingMessage(txn, m);
		txn.attach(new org.zerionproject.app.api.messaging.event
				.GroupTrInviteOfferReceivedEvent(contactId, m.getId(),
				grouptrGid, groupName, salt, creatorName, creatorPub,
				inviteTs, recordSig));
	}

	private void incomingGrouptrInviteResponse(Transaction txn, Message m,
			BdfDictionary meta, int messageType)
			throws DbException, FormatException {
		ContactId contactId = getContactId(txn, m.getGroupId());
		byte[] grouptrGid = meta.getRaw(MessagingConstants.MSG_KEY_GROUP_ID);
		long inviteTs = meta.getLong("gtrInviteTimestamp");
		byte[] recordSig = meta.getRaw(
				MessagingConstants.MSG_KEY_GROUP_RECORD_SIG);
		org.zerionproject.app.api.messaging.event
				.GroupTrInviteResponseReceivedEvent.Kind kind =
				messageType == MessageTypes.GROUPTR_INVITE_ACCEPT
						? org.zerionproject.app.api.messaging.event
								.GroupTrInviteResponseReceivedEvent.Kind
								.ACCEPT
						: org.zerionproject.app.api.messaging.event
								.GroupTrInviteResponseReceivedEvent.Kind
								.DECLINE;
		txn.attach(new org.zerionproject.app.api.messaging.event
				.GroupTrInviteResponseReceivedEvent(contactId, grouptrGid,
				inviteTs, recordSig, kind));
	}
}
