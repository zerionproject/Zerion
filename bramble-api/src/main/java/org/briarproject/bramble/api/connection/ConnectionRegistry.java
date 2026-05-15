package org.briarproject.bramble.api.connection;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.PendingContactId;
import org.briarproject.bramble.api.plugin.PluginConfig;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.event.ConnectionClosedEvent;
import org.briarproject.bramble.api.plugin.event.ConnectionOpenedEvent;
import org.briarproject.bramble.api.plugin.event.ContactConnectedEvent;
import org.briarproject.bramble.api.plugin.event.ContactDisconnectedEvent;
import org.briarproject.bramble.api.rendezvous.event.RendezvousConnectionClosedEvent;
import org.briarproject.bramble.api.rendezvous.event.RendezvousConnectionOpenedEvent;
import org.briarproject.bramble.api.sync.Priority;
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
