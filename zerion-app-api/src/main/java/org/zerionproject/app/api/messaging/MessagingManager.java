package org.zerionproject.app.api.messaging;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.FileTooBigException;
import org.zerionproject.app.api.conversation.ConversationManager.ConversationClient;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import javax.annotation.Nullable;

@NotNullByDefault
public interface MessagingManager extends ConversationClient {

	ClientId CLIENT_ID = new ClientId("org.zerionproject.app.messaging");

	int MAJOR_VERSION = 0;

	int MINOR_VERSION =
			org.zerionproject.core.api.contact.B3Constants.B3_PROOF_ENABLED
					? 6 : 5;

	int CHUNKED_VOICE_MIN_VERSION = 6;

	void addLocalMessage(PrivateMessage m) throws DbException;

	void addLocalMessage(Transaction txn, PrivateMessage m) throws DbException;

	void addLocalMeshMessage(Transaction txn, PrivateMessage m)
			throws DbException;

	void sendPrekeyBundle(ContactId contactId, byte[] bundle)
			throws DbException;

	void receiveMeshMessage(ContactId contactId, String text, long timestamp,
			byte[] meshSenderId, @Nullable byte[] parentMeshSenderId)
			throws DbException;

	void receiveMeshMessage(Transaction txn, ContactId contactId, String text,
			long timestamp, byte[] meshSenderId,
			@Nullable byte[] parentMeshSenderId) throws DbException;

	byte[] getMeshCanonicalId(MessageId localId) throws DbException;

	void receiveMeshAttachment(ContactId contactId, String contentType,
			byte[] imageBytes, long timestamp) throws DbException;

	PrivateMessageHeader addLocalMeshAttachment(ContactId contactId,
			String contentType, byte[] imageBytes, long timestamp)
			throws DbException;

	void receiveMeshGroupRecord(ContactId contactId, byte[] record,
			long timestamp) throws DbException;

	int MESH_STATE_PENDING = 0;
	int MESH_STATE_SENT = 1;
	int MESH_STATE_DELIVERED = 2;

	void setMeshMessageState(ContactId contactId, MessageId messageId, int state)
			throws DbException;

	java.util.List<UndeliveredMeshMessage> getUndeliveredMeshMessages()
			throws DbException;

	String MSG_KEY_MESH_GROUP_PENDING = "meshGroupPending";

	java.util.List<UndeliveredMeshGroupRecord> getUndeliveredMeshGroupRecords()
			throws DbException;

	void shareUndeliveredMeshGroupRecords() throws DbException;

	class UndeliveredMeshGroupRecord {
		public final ContactId contactId;
		public final byte[] record;
		public final long timestamp;

		public UndeliveredMeshGroupRecord(ContactId contactId, byte[] record,
				long timestamp) {
			this.contactId = contactId;
			this.record = record;
			this.timestamp = timestamp;
		}
	}

	class UndeliveredMeshMessage {
		public final ContactId contactId;
		public final MessageId messageId;
		public final String text;
		public final long timestamp;
		@Nullable
		public final byte[] replyToId;

		public UndeliveredMeshMessage(ContactId contactId, MessageId messageId,
				String text, long timestamp, @Nullable byte[] replyToId) {
			this.contactId = contactId;
			this.messageId = messageId;
			this.text = text;
			this.timestamp = timestamp;
			this.replyToId = replyToId;
		}
	}

	void addLocalVoiceSignal(VoiceSignal signal) throws DbException;

	AttachmentHeader addLocalAttachment(GroupId groupId, long timestamp,
			String contentType, InputStream is) throws DbException, IOException;

	AttachmentHeader addLocalAttachmentStreaming(GroupId groupId, long timestamp,
			String contentType, InputStream is, long totalSize,
			@Nullable ProgressCallback progressCallback) throws DbException, IOException;

	interface ProgressCallback {
		void onProgress(float progress);
	}

	void removeAttachment(AttachmentHeader header) throws DbException;

	ContactId getContactId(GroupId g) throws DbException;

	GroupId getConversationId(ContactId c) throws DbException;

	GroupId getConversationId(Transaction txn, ContactId c) throws DbException;

	@Nullable
	String getMessageText(MessageId m) throws DbException;

	@Nullable
	String getMessageText(Transaction txn, MessageId m) throws DbException;

	Map<MessageId, String> getMessageTexts(ContactId c) throws DbException;

	Map<MessageId, String> getMessageTexts(Transaction txn, ContactId c)
			throws DbException;

	PrivateMessageFormat getContactMessageFormat(Transaction txn, ContactId c)
			throws DbException;

	void addLocalReaction(ContactId contactId, MessageId targetMessageId,
			String emoji) throws DbException;

	java.util.Map<MessageId, java.util.Map<String, Integer>> getReactions(
			ContactId c) throws DbException;

	void sendTypingIndicator(ContactId contactId, boolean isTyping)
			throws DbException;

	java.util.Map<MessageId, LinkPreview> getLinkPreviews(ContactId c)
			throws DbException;

	void addLocalLinkPreviewMessage(Transaction txn, ContactId contactId,
			@Nullable String text, LinkPreview preview)
			throws DbException;
}
