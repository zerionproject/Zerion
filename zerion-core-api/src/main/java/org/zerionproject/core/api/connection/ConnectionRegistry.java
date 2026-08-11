package org.zerionproject.core.api.connection;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.plugin.PluginConfig;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.event.ConnectionClosedEvent;
import org.zerionproject.core.api.plugin.event.ConnectionOpenedEvent;
import org.zerionproject.core.api.plugin.event.ContactConnectedEvent;
import org.zerionproject.core.api.plugin.event.ContactDisconnectedEvent;
import org.zerionproject.core.api.rendezvous.event.RendezvousConnectionClosedEvent;
import org.zerionproject.core.api.rendezvous.event.RendezvousConnectionOpenedEvent;
import org.zerionproject.core.api.sync.Priority;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

@NotNullByDefault
public interface ConnectionRegistry {

	void registerIncomingConnection(ContactId c, TransportId t,
			InterruptibleConnection conn);

	void registerOutgoingConnection(ContactId c, TransportId t,
			InterruptibleConnection conn, Priority priority);

	void unregisterConnection(ContactId c, TransportId t,
			InterruptibleConnection conn, boolean incoming, boolean exception);

	void setPriority(ContactId c, TransportId t, InterruptibleConnection conn,
			Priority priority);

	Collection<ContactId> getConnectedContacts(TransportId t);

	Collection<ContactId> getConnectedOrBetterContacts(TransportId t);

	boolean isConnected(ContactId c, TransportId t);

	boolean isConnected(ContactId c);

	boolean registerConnection(PendingContactId p);

	void unregisterConnection(PendingContactId p, boolean success);
}
