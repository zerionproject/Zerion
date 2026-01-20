package org.briarproject.bramble.api.sync;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;

import javax.annotation.Nullable;

@NotNullByDefault
public interface SyncSessionFactory {

	/**
	 * Creates a session for receiving data from a contact.
	 *
	 * @param classical true for Briar-compatible record format,
	 *                  false for extended format
	 */
	SyncSession createIncomingSession(ContactId c, InputStream in,
			PriorityHandler handler, boolean classical);

	/**
	 * Creates a session for sending data to a contact over a simplex transport.
	 *
	 * @param eager True if messages should be sent eagerly, ie regardless of
	 * whether they're due for retransmission.
	 * @param classical true for Briar-compatible record format,
	 *                  false for extended format
	 */
	SyncSession createSimplexOutgoingSession(ContactId c, TransportId t,
			long maxLatency, boolean eager, StreamWriter streamWriter,
			boolean classical);

	/**
	 * Creates a session for sending data to a contact over a simplex transport
	 * with a session record for tracking sent/acked messages.
	 *
	 * @param classical true for Briar-compatible record format,
	 *                  false for extended format
	 */
	SyncSession createSimplexOutgoingSession(ContactId c, TransportId t,
			long maxLatency, StreamWriter streamWriter,
			OutgoingSessionRecord sessionRecord, boolean classical);

	/**
	 * Creates a session for sending data to a contact over a duplex transport.
	 *
	 * @param classical true for Briar-compatible record format,
	 *                  false for extended format
	 */
	SyncSession createDuplexOutgoingSession(ContactId c, TransportId t,
			long maxLatency, int maxIdleTime, StreamWriter streamWriter,
			@Nullable Priority priority, boolean classical);
}
