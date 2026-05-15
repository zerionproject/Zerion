package org.briarproject.briar.api.messaging;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.FileTooBigException;
import org.briarproject.briar.api.conversation.ConversationManager.ConversationClient;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

import javax.annotation.Nullable;

@NotNullByDefault
public interface MessagingManager extends ConversationClient {

	ClientId CLIENT_ID = new ClientId("org.briarproject.briar.messaging");

	int MAJOR_VERSION = 0;

	int MINOR_VERSION =
			org.briarproject.bramble.api.contact.B3Constants.B3_PROOF_ENABLED
					? 5 : 4;

	void addLocalMessage(PrivateMessage m) throws DbException;

	void addLocalMessage(Transaction txn, PrivateMessage m) throws DbException;

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
