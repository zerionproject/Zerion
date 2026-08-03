package org.zerionproject.core.api.sync.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class MessagesAckedEvent extends Event {

	private final ContactId contactId;
	private final Collection<MessageId> acked;

	public MessagesAckedEvent(ContactId contactId,
			Collection<MessageId> acked) {
		this.contactId = contactId;
		this.acked = acked;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public Collection<MessageId> getMessageIds() {
		return acked;
	}
}
