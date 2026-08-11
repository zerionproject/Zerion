package com.professor.zerion.android.attachment;

import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
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
