package org.zerionproject.core.api.contact.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ContactAddedEvent extends Event {

	private final ContactId contactId;
	private final boolean verified;

	public ContactAddedEvent(ContactId contactId, boolean verified) {
		this.contactId = contactId;
		this.verified = verified;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public boolean isVerified() {
		return verified;
	}
}
