package org.zerionproject.core.api.contact.request.event;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.contact.ContactId;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ContactRequestAddedEvent extends ContactRequestEvent {

	private final ContactId contactId;

	public ContactRequestAddedEvent(byte[] requestId, boolean outgoing,
			ContactId contactId) {
		super(requestId, outgoing);
		this.contactId = contactId;
	}

	public ContactId getContactId() {
		return contactId;
	}
}
