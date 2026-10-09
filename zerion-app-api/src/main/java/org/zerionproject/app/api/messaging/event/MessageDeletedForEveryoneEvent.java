package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class MessageDeletedForEveryoneEvent extends Event {

	private final ContactId contactId;
	private final MessageId messageId;
	private final boolean unread;

	public MessageDeletedForEveryoneEvent(ContactId contactId,
			MessageId messageId, boolean unread) {
		this.contactId = contactId;
		this.messageId = messageId;
		this.unread = unread;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public MessageId getMessageId() {
		return messageId;
	}

	public boolean wasUnread() {
		return unread;
	}
}
