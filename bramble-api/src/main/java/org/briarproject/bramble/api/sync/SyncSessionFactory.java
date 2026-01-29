package org.briarproject.bramble.api.sync;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;

import javax.annotation.Nullable;

@NotNullByDefault
public interface SyncSessionFactory {

	
	SyncSession createIncomingSession(ContactId c, InputStream in,
			PriorityHandler handler, boolean classical);

	
	SyncSession createSimplexOutgoingSession(ContactId c, TransportId t,
			long maxLatency, boolean eager, StreamWriter streamWriter,
			boolean classical);

	
	SyncSession createSimplexOutgoingSession(ContactId c, TransportId t,
			long maxLatency, StreamWriter streamWriter,
			OutgoingSessionRecord sessionRecord, boolean classical);

	
	SyncSession createDuplexOutgoingSession(ContactId c, TransportId t,
			long maxLatency, int maxIdleTime, StreamWriter streamWriter,
			@Nullable Priority priority, boolean classical);
}
