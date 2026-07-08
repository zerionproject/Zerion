package com.professor.zerion.android.attachment;

import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.Attachment;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.attachment.AttachmentReader;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import androidx.lifecycle.LiveData;

@NotNullByDefault
public interface AttachmentRetriever {

	AttachmentReader getAttachmentReader();

	@DatabaseExecutor
	Attachment getMessageAttachment(AttachmentHeader h) throws DbException;

	List<LiveData<AttachmentItem>> getAttachmentItems(
			PrivateMessageHeader messageHeader);

	@DatabaseExecutor
	void cacheAttachmentItemWithSize(MessageId conversationMessageId,
			AttachmentHeader h) throws DbException;

	AttachmentItem createAttachmentItem(Attachment a, boolean needsSize);

	@DatabaseExecutor
	void loadAttachmentItem(MessageId attachmentId);

}
