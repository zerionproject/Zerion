package org.zerionproject.core.api.contact.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ContactKeyRenewalEvent extends Event {

	private final ContactId contactId;
	private final boolean stalled;

	public ContactKeyRenewalEvent(ContactId contactId,
			boolean stalled) {
		this.contactId = contactId;
		this.stalled = stalled;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public boolean isStalled() {
		return stalled;
	}
}
