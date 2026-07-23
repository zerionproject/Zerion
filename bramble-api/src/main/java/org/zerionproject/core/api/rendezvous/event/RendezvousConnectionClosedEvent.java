package org.zerionproject.core.api.rendezvous.event;

import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.event.Event;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class RendezvousConnectionClosedEvent extends Event {

	private final PendingContactId pendingContactId;
	private final boolean success;

	public RendezvousConnectionClosedEvent(PendingContactId pendingContactId,
			boolean success) {
		this.pendingContactId = pendingContactId;
		this.success = success;
	}

	public PendingContactId getPendingContactId() {
		return pendingContactId;
	}

	public boolean isSuccess() {
		return success;
	}
}
