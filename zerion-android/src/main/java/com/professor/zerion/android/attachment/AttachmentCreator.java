package com.professor.zerion.android.attachment;

import android.net.Uri;

import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.messaging.PrivateMessageFormat;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;
import java.util.List;

import androidx.annotation.UiThread;
import androidx.lifecycle.LiveData;

@NotNullByDefault
public interface AttachmentCreator {

	@UiThread
	LiveData<AttachmentResult> storeAttachments(LiveData<GroupId> groupId,
			Collection<Uri> newUris, PrivateMessageFormat messageFormat);

	@UiThread
	LiveData<AttachmentResult> getLiveAttachments();

	@UiThread
	List<AttachmentHeader> getAttachmentHeadersForSending();

	@UiThread
	boolean hasValidAttachments();

	@UiThread
	void onAttachmentsSent(MessageId id);

	@UiThread
	void cancel();

	@UiThread
	void deleteUnsentAttachments();

	@IoExecutor
	void onAttachmentHeaderReceived(Uri uri, AttachmentHeader h,
			boolean needsSize);

	@IoExecutor
	void onAttachmentError(Uri uri, Throwable t);

	@IoExecutor
	void onAttachmentCreationFinished();

	@IoExecutor
	void onAttachmentProgress(Uri uri, float progress);
}