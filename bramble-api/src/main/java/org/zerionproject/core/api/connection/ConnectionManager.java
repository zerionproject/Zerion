package org.zerionproject.core.api.connection;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.sync.OutgoingSessionRecord;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ConnectionManager {

	void manageIncomingConnection(TransportId t, TransportConnectionReader r);

	void manageIncomingConnection(TransportId t, TransportConnectionReader r,
			TagController c);

	void manageIncomingConnection(TransportId t, DuplexTransportConnection d);

	void manageIncomingConnection(PendingContactId p, TransportId t,
			DuplexTransportConnection d, boolean classical);

	void manageOutgoingConnection(ContactId c, TransportId t,
			TransportConnectionWriter w);

	void manageOutgoingConnection(ContactId c, TransportId t,
			TransportConnectionWriter w, OutgoingSessionRecord sessionRecord);

	void manageOutgoingConnection(ContactId c, TransportId t,
			DuplexTransportConnection d);

	void manageOutgoingConnection(PendingContactId p, TransportId t,
			DuplexTransportConnection d, boolean classical);

	interface TagController {

		boolean shouldMarkTagAsRecognised(boolean exception);
	}
}
