package com.professor.zerion.android.mesh.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class MeshPresenceChangedEvent extends Event {

	private final ContactId contactId;
	private final boolean present;

	public MeshPresenceChangedEvent(ContactId contactId, boolean present) {
		this.contactId = contactId;
		this.present = present;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public boolean isPresent() {
		return present;
	}
}
