package org.zerionproject.core.api.contact.event;

import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class PendingContactRemovedEvent extends Event {

	private final PendingContactId id;

	public PendingContactRemovedEvent(PendingContactId id) {
		this.id = id;
	}

	public PendingContactId getId() {
		return id;
	}

}
