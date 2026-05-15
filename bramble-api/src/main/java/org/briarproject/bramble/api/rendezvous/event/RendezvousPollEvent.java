package org.briarproject.bramble.api.rendezvous.event;

import org.briarproject.bramble.api.contact.PendingContactId;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class RendezvousPollEvent extends Event {

	private final TransportId transportId;
	private final Collection<PendingContactId> pendingContacts;

	public RendezvousPollEvent(TransportId transportId,
			Collection<PendingContactId> pendingContacts) {
		this.transportId = transportId;
		this.pendingContacts = pendingContacts;
	}

	public TransportId getTransportId() {
		return transportId;
	}

	public Collection<PendingContactId> getPendingContacts() {
		return pendingContacts;
	}
}
