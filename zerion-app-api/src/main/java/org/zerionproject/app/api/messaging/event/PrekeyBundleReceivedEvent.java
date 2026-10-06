package org.zerionproject.app.api.messaging.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class PrekeyBundleReceivedEvent extends Event {

	private final ContactId contactId;
	private final byte[] bundle;

	public PrekeyBundleReceivedEvent(ContactId contactId, byte[] bundle) {
		this.contactId = contactId;
		this.bundle = bundle;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public byte[] getBundle() {
		return bundle;
	}
}
