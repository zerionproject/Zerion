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
	private final boolean addedDirectly;

	public ContactAddedEvent(ContactId contactId, boolean verified) {
		this(contactId, verified, verified);
	}

	public ContactAddedEvent(ContactId contactId, boolean verified,
			boolean addedDirectly) {
		this.contactId = contactId;
		this.verified = verified;
		this.addedDirectly = addedDirectly;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public boolean isVerified() {
		return verified;
	}

	public boolean isAddedDirectly() {
		return addedDirectly;
	}
}
