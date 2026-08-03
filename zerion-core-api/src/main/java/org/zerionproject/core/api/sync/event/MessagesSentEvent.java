package org.zerionproject.core.api.sync.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class MessagesSentEvent extends Event {

	private final ContactId contactId;
	private final Collection<MessageId> messageIds;
	private final long totalLength;

	public MessagesSentEvent(ContactId contactId,
			Collection<MessageId> messageIds, long totalLength) {
		this.contactId = contactId;
		this.messageIds = messageIds;
		this.totalLength = totalLength;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public Collection<MessageId> getMessageIds() {
		return messageIds;
	}

	public long getTotalLength() {
		return totalLength;
	}
}
