package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.sync.MessageId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class PrivateMessageEditedEvent extends Event {

	private final ContactId contactId;
	private final MessageId messageId;
	private final String text;
	private final boolean local;

	public PrivateMessageEditedEvent(ContactId contactId, MessageId messageId,
			String text, boolean local) {
		this.contactId = contactId;
		this.messageId = messageId;
		this.text = text;
		this.local = local;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public MessageId getMessageId() {
		return messageId;
	}

	public String getText() {
		return text;
	}

	public boolean isLocal() {
		return local;
	}
}
