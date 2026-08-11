package org.zerionproject.app.api.conversation.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ConversationMessageTrackedEvent extends Event {

	private final long timestamp;
	private final boolean read;
	private final ContactId contactId;

	public ConversationMessageTrackedEvent(long timestamp,
			boolean read, ContactId contactId) {
		this.timestamp = timestamp;
		this.read = read;
		this.contactId = contactId;
	}

	public long getTimestamp() {
		return timestamp;
	}

	public boolean getRead() {
		return read;
	}

	public ContactId getContactId() {
		return contactId;
	}
}
