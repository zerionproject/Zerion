package org.zerionproject.core.api.sync.event;

import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.validation.MessageState;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class MessageStateChangedEvent extends Event {

	private final MessageId messageId;
	private final boolean local;
	private final MessageState state;

	public MessageStateChangedEvent(MessageId messageId, boolean local,
			MessageState state) {
		this.messageId = messageId;
		this.local = local;
		this.state = state;
	}

	public MessageId getMessageId() {
		return messageId;
	}

	public boolean isLocal() {
		return local;
	}

	public MessageState getState() {
		return state;
	}

}
