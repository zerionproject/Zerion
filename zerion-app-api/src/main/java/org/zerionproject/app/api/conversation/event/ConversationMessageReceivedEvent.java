package org.zerionproject.app.api.conversation.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public abstract class ConversationMessageReceivedEvent<H extends ConversationMessageHeader>
		extends Event {

	private final H messageHeader;
	private final ContactId contactId;

	public ConversationMessageReceivedEvent(H messageHeader,
			ContactId contactId) {
		this.messageHeader = messageHeader;
		this.contactId = contactId;
	}

	public H getMessageHeader() {
		return messageHeader;
	}

	public ContactId getContactId() {
		return contactId;
	}
}
