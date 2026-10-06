package org.zerionproject.core.api.contact.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ContactConnectionKeysEvent extends Event {

	private final ContactId contactId;
	private final boolean outOfSync;

	public ContactConnectionKeysEvent(ContactId contactId,
			boolean outOfSync) {
		this.contactId = contactId;
		this.outOfSync = outOfSync;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public boolean isOutOfSync() {
		return outOfSync;
	}
}
