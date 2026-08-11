package org.zerionproject.app.api.messaging;

import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.conversation.ConversationMessageVisitor;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class PrivateMessageHeader extends ConversationMessageHeader {

	private final boolean hasText;
	private final List<AttachmentHeader> attachmentHeaders;
	@Nullable
	private final MessageId replyToId;
	private final boolean mesh;

	public PrivateMessageHeader(MessageId id, GroupId groupId, long timestamp,
			boolean local, boolean read, boolean sent, boolean seen,
			boolean hasText, List<AttachmentHeader> headers,
			long autoDeleteTimer) {
		this(id, groupId, timestamp, local, read, sent, seen, hasText,
				headers, autoDeleteTimer, null);
	}

	public PrivateMessageHeader(MessageId id, GroupId groupId, long timestamp,
			boolean local, boolean read, boolean sent, boolean seen,
			boolean hasText, List<AttachmentHeader> headers,
			long autoDeleteTimer, @Nullable MessageId replyToId) {
		this(id, groupId, timestamp, local, read, sent, seen, hasText,
				headers, autoDeleteTimer, replyToId, false);
	}

	public PrivateMessageHeader(MessageId id, GroupId groupId, long timestamp,
			boolean local, boolean read, boolean sent, boolean seen,
			boolean hasText, List<AttachmentHeader> headers,
			long autoDeleteTimer, @Nullable MessageId replyToId, boolean mesh) {
		super(id, groupId, timestamp, local, read, sent, seen, autoDeleteTimer);
		this.hasText = hasText;
		this.attachmentHeaders = headers;
		this.replyToId = replyToId;
		this.mesh = mesh;
	}

	/** True if this message was delivered over the offline mesh. */
	public boolean isMesh() {
		return mesh;
	}

	public boolean hasText() {
		return hasText;
	}

	public List<AttachmentHeader> getAttachmentHeaders() {
		return attachmentHeaders;
	}

	@Nullable
	public MessageId getReplyToId() {
		return replyToId;
	}

	@Override
	public <T> T accept(ConversationMessageVisitor<T> v) {
		return v.visitPrivateMessageHeader(this);
	}
}
