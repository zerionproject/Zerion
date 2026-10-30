package org.zerionproject.app.api.messaging;

import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class PinnedMessage {

	private final MessageId messageId;
	private final boolean local;
	private final long timestamp;

	public PinnedMessage(MessageId messageId, boolean local, long timestamp) {
		this.messageId = messageId;
		this.local = local;
		this.timestamp = timestamp;
	}

	public MessageId getMessageId() {
		return messageId;
	}

	public boolean isLocal() {
		return local;
	}

	public long getTimestamp() {
		return timestamp;
	}
}
