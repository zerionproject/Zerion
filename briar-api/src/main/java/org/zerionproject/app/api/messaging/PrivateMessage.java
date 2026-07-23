package org.zerionproject.app.api.messaging;

import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static java.util.Collections.emptyList;
import static org.zerionproject.app.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.zerionproject.app.api.messaging.PrivateMessageFormat.TEXT_IMAGES;
import static org.zerionproject.app.api.messaging.PrivateMessageFormat.TEXT_IMAGES_AUTO_DELETE;
import static org.zerionproject.app.api.messaging.PrivateMessageFormat.TEXT_ONLY;

@Immutable
@NotNullByDefault
public class PrivateMessage {

	private final Message message;
	private final boolean hasText;
	private final List<AttachmentHeader> attachmentHeaders;
	private final long autoDeleteTimer;
	private final PrivateMessageFormat format;
	@Nullable
	private final MessageId replyToId;

	public PrivateMessage(Message message) {
		this.message = message;
		hasText = true;
		attachmentHeaders = emptyList();
		autoDeleteTimer = NO_AUTO_DELETE_TIMER;
		format = TEXT_ONLY;
		replyToId = null;
	}

	public PrivateMessage(Message message, boolean hasText,
			List<AttachmentHeader> headers) {
		this.message = message;
		this.hasText = hasText;
		this.attachmentHeaders = headers;
		autoDeleteTimer = NO_AUTO_DELETE_TIMER;
		format = TEXT_IMAGES;
		replyToId = null;
	}

	public PrivateMessage(Message message, boolean hasText,
			List<AttachmentHeader> headers, long autoDeleteTimer) {
		this(message, hasText, headers, autoDeleteTimer, null);
	}

	public PrivateMessage(Message message, boolean hasText,
			List<AttachmentHeader> headers, long autoDeleteTimer,
			@Nullable MessageId replyToId) {
		this.message = message;
		this.hasText = hasText;
		this.attachmentHeaders = headers;
		this.autoDeleteTimer = autoDeleteTimer;
		this.replyToId = replyToId;
		format = TEXT_IMAGES_AUTO_DELETE;
	}

	public Message getMessage() {
		return message;
	}

	public PrivateMessageFormat getFormat() {
		return format;
	}

	public boolean hasText() {
		return hasText;
	}

	public List<AttachmentHeader> getAttachmentHeaders() {
		return attachmentHeaders;
	}

	public long getAutoDeleteTimer() {
		return autoDeleteTimer;
	}

	@Nullable
	public MessageId getReplyToId() {
		return replyToId;
	}
}
