package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.MessageStatus;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.grouptr.GroupTrInvitationHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.MessagingManager.UndeliveredMeshGroupRecord;
import org.zerionproject.app.api.messaging.MessagingManager.UndeliveredMeshMessage;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import static java.util.Collections.emptyList;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.app.api.messaging.MessagingManager.MESH_STATE_DELIVERED;
import static org.zerionproject.app.api.messaging.MessagingManager.MESH_STATE_PENDING;
import static org.zerionproject.app.api.messaging.MessagingManager.MESH_STATE_SENT;
import static org.zerionproject.app.api.messaging.MessagingManager.MSG_KEY_MESH_GROUP_PENDING;
import static org.zerionproject.app.client.MessageTrackerConstants.MSG_KEY_READ;
import static org.zerionproject.app.messaging.MessageTypes.GROUPTR_INVITE_OFFER;
import static org.zerionproject.app.messaging.MessageTypes.LINK_PREVIEW_MESSAGE;
import static org.zerionproject.app.messaging.MessageTypes.PRIVATE_MESSAGE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_ATTACHMENT_HEADERS;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_AUTO_DELETE_TIMER;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GROUP_ID;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GTR_INVITE_CREATOR_NAME;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GTR_INVITE_CREATOR_PUB;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GTR_INVITE_NAME;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_GTR_INVITE_SALT;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_HAS_TEXT;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_LOCAL;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MESH;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MESH_SENDER_ID;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MESH_STATE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MSG_TYPE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_REPLY_TO_ID;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_TIMESTAMP;

@NotNullByDefault
final class WholeGroupScans {

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final MessagingManager messagingManager;

	WholeGroupScans(DatabaseComponent db, ClientHelper clientHelper,
			MessagingManager messagingManager) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.messagingManager = messagingManager;
	}

	List<ConversationMessageHeader> headers(Transaction txn, ContactId c)
			throws Exception {
		GroupId g = contactGroup(txn, c);
		Map<MessageId, BdfDictionary> metadata =
				clientHelper.getMessageMetadataAsDictionary(txn, g);
		List<ConversationMessageHeader> headers = new ArrayList<>();
		for (MessageStatus s : db.getMessageStatus(txn, c, g)) {
			MessageId id = s.getMessageId();
			BdfDictionary meta = metadata.get(id);
			if (meta == null) continue;
			Integer messageType = meta.getOptionalInt(MSG_KEY_MSG_TYPE);
			if (messageType != null && messageType == GROUPTR_INVITE_OFFER) {
				if (meta.getBoolean(MSG_KEY_LOCAL, false)) continue;
				byte[] grouptrGidRaw = meta.getOptionalRaw(MSG_KEY_GROUP_ID);
				if (grouptrGidRaw == null) continue;
				String groupName = meta.getOptionalString(
						MSG_KEY_GTR_INVITE_NAME);
				byte[] salt = meta.getOptionalRaw(MSG_KEY_GTR_INVITE_SALT);
				String creatorName = meta.getOptionalString(
						MSG_KEY_GTR_INVITE_CREATOR_NAME);
				byte[] creatorPub = meta.getOptionalRaw(
						MSG_KEY_GTR_INVITE_CREATOR_PUB);
				long inviteTs = meta.getLong("gtrInviteTimestamp");
				long ts = meta.getLong(MSG_KEY_TIMESTAMP);
				boolean read = meta.getBoolean(MSG_KEY_READ, false);
				if (salt == null || creatorPub == null
						|| creatorName == null || groupName == null) {
					continue;
				}
				headers.add(new GroupTrInvitationHeader(id, g, ts, false, read,
						s.isSent(), s.isSeen(), new GroupId(grouptrGidRaw),
						groupName, salt, creatorName, creatorPub, inviteTs));
				continue;
			}
			if (messageType != null && messageType != PRIVATE_MESSAGE
					&& messageType != LINK_PREVIEW_MESSAGE) {
				continue;
			}
			Long timestampOpt = meta.getOptionalLong(MSG_KEY_TIMESTAMP);
			if (timestampOpt == null) continue;
			long timestamp = timestampOpt;
			boolean local = meta.getBoolean(MSG_KEY_LOCAL);
			boolean read = meta.getBoolean(MSG_KEY_READ);
			boolean mesh = meta.getBoolean(MSG_KEY_MESH, false);
			long meshState = mesh ? meta.getLong(MSG_KEY_MESH_STATE,
					(long) MESH_STATE_PENDING) : 0;
			boolean sent = mesh ? meshState >= MESH_STATE_SENT : s.isSent();
			boolean seen = mesh ? meshState >= MESH_STATE_DELIVERED
					: s.isSeen();
			if (messageType == null) {
				headers.add(new PrivateMessageHeader(id, g, timestamp, local,
						read, sent, seen, true, emptyList(),
						NO_AUTO_DELETE_TIMER, null, mesh));
			} else {
				boolean hasText = meta.getBoolean(MSG_KEY_HAS_TEXT);
				long timer = meta.getLong(MSG_KEY_AUTO_DELETE_TIMER,
						NO_AUTO_DELETE_TIMER);
				byte[] replyToIdBytes = meta.getOptionalRaw(MSG_KEY_REPLY_TO_ID);
				MessageId replyToId = replyToIdBytes != null ?
						new MessageId(replyToIdBytes) : null;
				headers.add(new PrivateMessageHeader(id, g, timestamp, local,
						read, sent, seen, hasText,
						attachmentHeaders(g, meta), timer, replyToId, mesh));
			}
		}
		return headers;
	}

	Set<MessageId> messageIds(Transaction txn, ContactId c) throws Exception {
		Set<MessageId> result = new HashSet<>();
		for (Map.Entry<MessageId, BdfDictionary> e : clientHelper
				.getMessageMetadataAsDictionary(txn, contactGroup(txn, c))
				.entrySet()) {
			Integer type = e.getValue().getOptionalInt(MSG_KEY_MSG_TYPE);
			if (type == null || type == PRIVATE_MESSAGE
					|| type == LINK_PREVIEW_MESSAGE) {
				result.add(e.getKey());
			}
		}
		return result;
	}

	Map<MessageId, String> texts(Transaction txn, ContactId c)
			throws Exception {
		Map<MessageId, String> texts = new HashMap<>();
		for (Map.Entry<MessageId, BdfDictionary> e : clientHelper
				.getMessageMetadataAsDictionary(txn, contactGroup(txn, c))
				.entrySet()) {
			BdfDictionary meta = e.getValue();
			Integer messageType = meta.getOptionalInt(MSG_KEY_MSG_TYPE);
			if (messageType != null && messageType != PRIVATE_MESSAGE
					&& messageType != LINK_PREVIEW_MESSAGE) {
				continue;
			}
			boolean hasText = messageType == null
					|| meta.getBoolean(MSG_KEY_HAS_TEXT, false);
			if (!hasText) continue;
			try {
				BdfList body = clientHelper.getMessageAsList(txn, e.getKey());
				String text = body.size() == 1 ? body.getString(0)
						: body.getOptionalString(1);
				if (text != null) texts.put(e.getKey(), text);
			} catch (FormatException | NoSuchMessageException ex) {
			}
		}
		return texts;
	}

	List<Integer> counts(Transaction txn, GroupId g) throws Exception {
		int msgCount = 0;
		int unreadCount = 0;
		for (BdfDictionary meta : clientHelper
				.getMessageMetadataAsDictionary(txn, g).values()) {
			Integer messageType = meta.getOptionalInt(MSG_KEY_MSG_TYPE);
			boolean receivedOffer = messageType != null
					&& messageType == GROUPTR_INVITE_OFFER
					&& !meta.getBoolean(MSG_KEY_LOCAL, false)
					&& meta.getOptionalLong(MSG_KEY_TIMESTAMP) != null;
			if (receivedOffer || messageType == null
					|| messageType == PRIVATE_MESSAGE
					|| messageType == LINK_PREVIEW_MESSAGE) {
				msgCount++;
				if (!meta.getBoolean(MSG_KEY_READ, false)) unreadCount++;
			}
		}
		return Arrays.asList(msgCount, unreadCount);
	}

	@Nullable
	MessageId meshParent(Transaction txn, GroupId g, byte[] canonicalId)
			throws Exception {
		try {
			for (Map.Entry<MessageId, BdfDictionary> e : clientHelper
					.getMessageMetadataAsDictionary(txn, g).entrySet()) {
				if (Arrays.equals(e.getKey().getBytes(), canonicalId)) {
					return e.getKey();
				}
				byte[] sid =
						e.getValue().getOptionalRaw(MSG_KEY_MESH_SENDER_ID);
				if (sid != null && Arrays.equals(sid, canonicalId)) {
					return e.getKey();
				}
			}
		} catch (FormatException e) {
		}
		return null;
	}

	List<UndeliveredMeshMessage> undeliveredMeshMessages(Transaction txn)
			throws Exception {
		List<UndeliveredMeshMessage> result = new ArrayList<>();
		for (Contact contact : db.getContacts(txn)) {
			GroupId g = messagingManager.getContactGroup(contact).getId();
			for (Map.Entry<MessageId, BdfDictionary> e : clientHelper
					.getMessageMetadataAsDictionary(txn, g).entrySet()) {
				BdfDictionary meta = e.getValue();
				if (!meta.getBoolean(MSG_KEY_MESH, false)) continue;
				if (!meta.getBoolean(MSG_KEY_LOCAL, false)) continue;
				long state = meta.getLong(MSG_KEY_MESH_STATE,
						(long) MESH_STATE_PENDING);
				if (state >= MESH_STATE_DELIVERED) continue;
				BdfList body = clientHelper.getMessageAsList(txn, e.getKey());
				String text = body.size() == 1 ? body.getString(0)
						: body.getOptionalString(1);
				result.add(new UndeliveredMeshMessage(contact.getId(),
						e.getKey(), text, meta.getLong(MSG_KEY_TIMESTAMP, 0L),
						meta.getOptionalRaw(MSG_KEY_REPLY_TO_ID)));
			}
		}
		return result;
	}

	List<UndeliveredMeshGroupRecord> undeliveredMeshGroupRecords(
			Transaction txn) throws Exception {
		List<UndeliveredMeshGroupRecord> result = new ArrayList<>();
		for (Contact contact : db.getContacts(txn)) {
			for (MessageId id : pendingMeshGroupRecords(txn, contact)) {
				Message m = clientHelper.getMessage(txn, id);
				result.add(new UndeliveredMeshGroupRecord(contact.getId(),
						m.getBody(), m.getTimestamp()));
			}
		}
		return result;
	}

	Set<MessageId> pendingMeshGroupRecords(Transaction txn) throws Exception {
		Set<MessageId> pending = new HashSet<>();
		for (Contact contact : db.getContacts(txn)) {
			pending.addAll(pendingMeshGroupRecords(txn, contact));
		}
		return pending;
	}

	private List<MessageId> pendingMeshGroupRecords(Transaction txn,
			Contact contact) throws Exception {
		List<MessageId> pending = new ArrayList<>();
		GroupId g = messagingManager.getContactGroup(contact).getId();
		for (Map.Entry<MessageId, BdfDictionary> e : clientHelper
				.getMessageMetadataAsDictionary(txn, g).entrySet()) {
			if (e.getValue().getBoolean(MSG_KEY_MESH_GROUP_PENDING, false)) {
				pending.add(e.getKey());
			}
		}
		return pending;
	}

	private GroupId contactGroup(Transaction txn, ContactId c)
			throws DbException {
		return messagingManager.getContactGroup(db.getContact(txn, c))
				.getId();
	}

	private static List<AttachmentHeader> attachmentHeaders(GroupId g,
			BdfDictionary meta) throws FormatException {
		BdfList list = meta.getList(MSG_KEY_ATTACHMENT_HEADERS);
		List<AttachmentHeader> headers = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			BdfList header = list.getList(i);
			headers.add(new AttachmentHeader(g,
					new MessageId(header.getRaw(0)), header.getString(1)));
		}
		return headers;
	}
}
