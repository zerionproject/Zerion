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

	/**
	 * The unique ID of the messaging client.
	 */
	ClientId CLIENT_ID = new ClientId("org.briarproject.briar.messaging");

	/**
	 * The current major version of the messaging client.
	 */
	int MAJOR_VERSION = 0;

	/**
	 * The current minor version of the messaging client.
	 * Version 4 adds support for chunked attachments (video/audio).
	 */
	int MINOR_VERSION = 4;

	/**
	 * Stores a local private message.
	 */
	void addLocalMessage(PrivateMessage m) throws DbException;

	/**
	 * Stores a local private message.
	 */
	void addLocalMessage(Transaction txn, PrivateMessage m) throws DbException;

	/**
	 * Stores a local voice signal message.
	 */
	void addLocalVoiceSignal(VoiceSignal signal) throws DbException;

	/**
	 * Stores a local attachment message.
	 * Note: This method loads the entire attachment into memory. For large
	 * files (videos, audio), use addLocalAttachmentStreaming() instead.
	 *
	 * @throws FileTooBigException If the attachment is too big
	 */
	AttachmentHeader addLocalAttachment(GroupId groupId, long timestamp,
			String contentType, InputStream is) throws DbException, IOException;

	/**
	 * Stores a local attachment message using streaming to avoid loading
	 * the entire file into memory. Suitable for large files like videos.
	 *
	 * @param groupId The group ID for the conversation
	 * @param timestamp The message timestamp
	 * @param contentType The MIME type of the attachment
	 * @param is The input stream containing the attachment data
	 * @param totalSize The total size of the attachment in bytes
	 * @param progressCallback Optional callback for progress updates (0.0-1.0)
	 * @throws FileTooBigException If the attachment exceeds the maximum size
	 */
	AttachmentHeader addLocalAttachmentStreaming(GroupId groupId, long timestamp,
			String contentType, InputStream is, long totalSize,
			@Nullable ProgressCallback progressCallback) throws DbException, IOException;

	/**
	 * Callback interface for attachment upload progress.
	 */
	interface ProgressCallback {
		void onProgress(float progress);
	}

	/**
	 * Removes an unsent attachment.
	 */
	void removeAttachment(AttachmentHeader header) throws DbException;

	/**
	 * Returns the ID of the contact with the given private conversation.
	 */
	ContactId getContactId(GroupId g) throws DbException;

	/**
	 * Returns the ID of the private conversation with the given contact.
	 */
	GroupId getConversationId(ContactId c) throws DbException;

	/**
	 * Returns the ID of the private conversation with the given contact.
	 */
	GroupId getConversationId(Transaction txn, ContactId c) throws DbException;

	/**
	 * Returns the text of the private message with the given ID, or null if
	 * the private message has no text.
	 */
	@Nullable
	String getMessageText(MessageId m) throws DbException;

	/**
	 * Returns the text of the private message with the given ID, or null if
	 * the private message has no text.
	 */
	@Nullable
	String getMessageText(Transaction txn, MessageId m) throws DbException;

	/**
	 * Returns a map of message IDs to message text for all messages with text
	 * in the given contact's conversation. This is more efficient than calling
	 * getMessageText() individually for each message.
	 */
	Map<MessageId, String> getMessageTexts(ContactId c) throws DbException;

	/**
	 * Returns a map of message IDs to message text for all messages with text
	 * in the given contact's conversation. This is more efficient than calling
	 * getMessageText() individually for each message.
	 */
	Map<MessageId, String> getMessageTexts(Transaction txn, ContactId c)
			throws DbException;

	/**
	 * Returns the private message format supported by the given contact.
	 */
	PrivateMessageFormat getContactMessageFormat(Transaction txn, ContactId c)
			throws DbException;

	/**
	 * Sends a local reaction to the given target message.
	 */
	void addLocalReaction(ContactId contactId, MessageId targetMessageId,
			String emoji) throws DbException;

	/**
	 * Returns all reactions for messages in the given contact's conversation.
	 * The outer map is keyed by target message ID, the inner map is
	 * emoji -> count.
	 */
	java.util.Map<MessageId, java.util.Map<String, Integer>> getReactions(
			ContactId c) throws DbException;

	/**
	 * Sends a typing indicator to the given contact.
	 */
	void sendTypingIndicator(ContactId contactId, boolean isTyping)
			throws DbException;

	/**
	 * Returns link previews for messages in the given contact's conversation.
	 * Map is keyed by message ID.
	 */
	java.util.Map<MessageId, LinkPreview> getLinkPreviews(ContactId c)
			throws DbException;

	/**
	 * Sends a private message with an embedded link preview.
	 */
	void addLocalLinkPreviewMessage(Transaction txn, ContactId contactId,
			@Nullable String text, LinkPreview preview)
			throws DbException;
}
