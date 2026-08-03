package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class TypingIndicatorReceivedEvent extends Event {

	private final ContactId contactId;
	private final boolean typing;

	public TypingIndicatorReceivedEvent(ContactId contactId, boolean typing) {
		this.contactId = contactId;
		this.typing = typing;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public boolean isTyping() {
		return typing;
	}
}
