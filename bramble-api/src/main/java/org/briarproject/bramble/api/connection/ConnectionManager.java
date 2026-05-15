package org.briarproject.bramble.api.connection;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.PendingContactId;
import org.briarproject.bramble.api.plugin.TransportConnectionReader;
import org.briarproject.bramble.api.plugin.TransportConnectionWriter;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.sync.OutgoingSessionRecord;
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
