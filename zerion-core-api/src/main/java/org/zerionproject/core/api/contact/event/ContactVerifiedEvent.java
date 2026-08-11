package org.zerionproject.core.api.contact.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ContactVerifiedEvent extends Event {

	private final ContactId contactId;

	public ContactVerifiedEvent(ContactId contactId) {
		this.contactId = contactId;
	}

	public ContactId getContactId() {
		return contactId;
	}

}
