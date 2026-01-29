package org.briarproject.bramble.api.contact.event;

import org.briarproject.bramble.api.contact.PendingContact;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;


@Immutable
@NotNullByDefault
public class PendingContactAddedEvent extends Event {

	private final PendingContact pendingContact;

	public PendingContactAddedEvent(PendingContact pendingContact) {
		this.pendingContact = pendingContact;
	}

	public PendingContact getPendingContact() {
		return pendingContact;
	}
}
