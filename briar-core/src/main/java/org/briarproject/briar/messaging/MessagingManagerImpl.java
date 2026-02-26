package org.briarproject.briar.messaging;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.cleanup.CleanupHook;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager.ContactHook;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.NoSuchMessageException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.Group.Visibility;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.MessageStatus;
import org.briarproject.bramble.api.sync.validation.IncomingMessageHook;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.bramble.api.versioning.ClientVersioningManager.ClientVersioningHook;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.autodelete.AutoDeleteManager;
import org.briarproject.briar.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.client.MessageTracker.GroupCount;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.conversation.ConversationManager.ConversationClient;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.conversation.DeletionResult;
import org.briarproject.briar.api.messaging.LinkPreview;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessage;
import org.briarproject.briar.api.messaging.PrivateMessageFormat;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.briar.api.messaging.VoiceSignal;
import org.briarproject.briar.api.messaging.VoiceSignalHeader;
import org.briarproject.briar.api.messaging.VoiceSignalType;
import org.briarproject.briar.api.messaging.event.AttachmentReceivedEvent;
import org.briarproject.briar.api.messaging.event.PrivateMessageReceivedEvent;
import org.briarproject.briar.api.messaging.event.ReactionReceivedEvent;
import org.briarproject.briar.api.messaging.event.TypingIndicatorReceivedEvent;
import org.briarproject.briar.api.messaging.event.VoiceSignalReceivedEvent;
import org.briarproject.nullsafety.NotNullByDefault;

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
import static org.briarproject.bramble.api.client.ContactGroupConstants.GROUP_KEY_CONTACT_ID;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.bramble.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.briarproject.bramble.util.IoUtils.copyAndClose;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.briarproject.briar.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.briarproject.briar.api.messaging.PrivateMessageFormat.TEXT_IMAGES;
import static org.briarproject.briar.api.messaging.PrivateMessageFormat.TEXT_IMAGES_AUTO_DELETE;
import static org.briarproject.briar.api.messaging.PrivateMessageFormat.TEXT_IMAGES_CHUNKED;
import static org.briarproject.briar.api.messaging.PrivateMessageFormat.TEXT_ONLY;
import static org.briarproject.briar.client.MessageTrackerConstants.MSG_KEY_READ;
import static org.briarproject.briar.messaging.MessageTypes.ATTACHMENT;
import static org.briarproject.briar.messaging.MessageTypes.ATTACHMENT_CHUNK;
import static org.briarproject.briar.messaging.MessageTypes.ATTACHMENT_MANIFEST;
import static org.briarproject.briar.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.briarproject.briar.messaging.MessagingConstants.MISSING_ATTACHMENT_CLEANUP_DURATION_MS;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_ATTACHMENT_HEADERS;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_AUTO_DELETE_TIMER;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_HAS_TEXT;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_IS_TYPING;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_LOCAL;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_MSG_TYPE;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_HAS_PREVIEW_IMAGE;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_PREVIEW_DESCRIPTION;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_PREVIEW_TITLE;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_PREVIEW_URL;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_REACTION_EMOJI;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_REPLY_TO_ID;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_TARGET_MESSAGE_ID;
import static org.briarproject.briar.messaging.MessagingConstants.MSG_KEY_TIMESTAMP;

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
			StreamingAttachmentWriter streamingAttachmentWriter) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.metadataParser = metadataParser;
		this.conversationManager = conversationManager;
		this.messageTracker = messageTracker;
		this.clientVersioningManager = clientVersioningManager;
		this.contactGroupFactory = contactGroupFactory;
		this.autoDeleteManager = autoDeleteManager;
		this.streamingAttachmentWriter = streamingAttachmentWriter;
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

	// Only purge ephemeral messages older than this threshold so that
	// in-flight call signals survive a quick app restart.
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
			} else {
				throw new InvalidMessageException();
			}
		} catch (FormatException e) {
			throw new InvalidMessageException(e);
		}
		return ACCEPT_DO_NOT_SHARE;
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
		// Do NOT call db.removeMessage() here — the caller
		// (ValidationManagerImpl) will call setMessageState(DELIVERED)
		// on this message after we return. Removing it here causes
		// setMessageState to throw NoSuchMessageException, which
		// rolls back the transaction and prevents the event from firing.
		// Voice signals are cleaned up by purgeStaleEphemeralMessages().
	}

	@Override
	public void addLocalMessage(PrivateMessage m) throws DbException {
		db.transaction(false, txn -> addLocalMessage(txn, m));
	}

	@Override
	public void addLocalMessage(Transaction txn, PrivateMessage m)
			throws DbException {
		try {
			long timer = m.getAutoDeleteTimer();
			BdfDictionary meta = new BdfDictionary();
			meta.put(MSG_KEY_TIMESTAMP, m.getMessage().getTimestamp());
			meta.put(MSG_KEY_LOCAL, true);
			meta.put(MSG_KEY_READ, true);
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
			clientHelper.addLocalMessage(txn, m.getMessage(), meta, true,
					false);
			if (timer != NO_AUTO_DELETE_TIMER) {
				db.setCleanupTimerDuration(txn, m.getMessage().getId(), timer);
			}
			conversationManager.trackOutgoingMessage(txn, m.getMessage());
		} catch (FormatException e) {
			throw new AssertionError(e);
		}
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
				if (messageType != null && messageType != PRIVATE_MESSAGE
						&& messageType != MessageTypes.LINK_PREVIEW_MESSAGE)
					continue;
				long timestamp = meta.getLong(MSG_KEY_TIMESTAMP);
				boolean local = meta.getBoolean(MSG_KEY_LOCAL);
				boolean read = meta.getBoolean(MSG_KEY_READ);
				if (messageType == null) {
					headers.add(new PrivateMessageHeader(id, g, timestamp,
							local, read, s.isSent(), s.isSeen(), true,
							emptyList(), NO_AUTO_DELETE_TIMER));
				} else {
					boolean hasText = meta.getBoolean(MSG_KEY_HAS_TEXT);
					long timer = meta.getLong(MSG_KEY_AUTO_DELETE_TIMER,
							NO_AUTO_DELETE_TIMER);
					byte[] replyToIdBytes =
							meta.getOptionalRaw(MSG_KEY_REPLY_TO_ID);
					MessageId replyToId = replyToIdBytes != null ?
							new MessageId(replyToIdBytes) : null;
					headers.add(new PrivateMessageHeader(id, g, timestamp,
							local, read, s.isSent(), s.isSeen(), hasText,
							parseAttachmentHeaders(g, meta), timer,
							replyToId));
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
						org.briarproject.bramble.api.db.NoSuchMessageException e) {
					// Message body was deleted — skip this message
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
		// Bulk delete: explicitly removes from statuses, messageMetadata,
		// messageDependencies, and messages tables by groupId.
		// Cannot rely on ON DELETE CASCADE — SQLite PRAGMA foreign_keys
		// is OFF so cascades do not fire.
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
				for (AttachmentHeader h : parseAttachmentHeaders(g, meta)) {
					try {
						db.removeMessage(txn, h.getMessageId());
					} catch (NoSuchMessageException e) {
					}
				}
			}
			db.removeMessage(txn, m);
		} catch (NoSuchMessageException e) {
			// Message already deleted — skip
		} catch (FormatException e) {
			throw new DbException(e);
		}
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

		// Idempotency: check for existing remote reaction with same
		// target + emoji — if found, remove old one (toggle off)
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
				// Toggle off: remove old reaction, keep the new one
				// (which replaces it). Delete the old entry.
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
		// Do NOT call db.removeMessage() here — same reason as
		// incomingVoiceSignal: caller calls setMessageState(DELIVERED)
		// after we return, which would throw on a removed message.
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
		// Zerion-only: all peers run Zerion with MINOR_VERSION 4.
		// The version negotiation may return 0 before sync completes,
		// so we default to true to avoid silently blocking features.
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

				// Idempotency: check for existing local reaction with
				// same target + emoji — if found, remove it (toggle off)
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
						// Toggle off: remove existing reaction
						db.removeMessage(txn, entry.getKey());
						ReactionReceivedEvent event =
								new ReactionReceivedEvent(contactId,
										targetMessageId, emoji, true);
						txn.attach(event);
						return;
					}
				}

				// No existing match — add new reaction
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
								org.briarproject.bramble.api.db.NoSuchMessageException ignored) {
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
}
