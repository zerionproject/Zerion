package org.zerionproject.core.api.rendezvous.event;

import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class RendezvousConnectionOpenedEvent extends Event {

	private final PendingContactId pendingContactId;

	public RendezvousConnectionOpenedEvent(PendingContactId pendingContactId) {
		this.pendingContactId = pendingContactId;
	}

	public PendingContactId getPendingContactId() {
		return pendingContactId;
	}
}
