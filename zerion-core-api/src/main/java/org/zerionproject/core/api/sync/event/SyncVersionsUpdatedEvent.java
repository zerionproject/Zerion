package org.zerionproject.core.api.sync.event;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class SyncVersionsUpdatedEvent extends Event {

	private final ContactId contactId;
	private final List<Byte> supported;

	public SyncVersionsUpdatedEvent(ContactId contactId, List<Byte> supported) {
		this.contactId = contactId;
		this.supported = supported;
	}

	public ContactId getContactId() {
		return contactId;
	}

	public List<Byte> getSupportedVersions() {
		return supported;
	}
}
