package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.TransportId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Handles a connected transport socket's streams. The Tor transport is
 * responsible only for producing connected sockets (dial and accept); what
 * happens on them (running the pairing handshake, or resuming a contact's
 * stored session and carrying messages) lives behind this seam.
 *
 * <p>Outgoing connections are dialled to a known contact, so the contact id is
 * supplied. Incoming connections are anonymous until the stream's tag is
 * recognised, so the handler resolves the contact itself.
 *
 * <p>Both methods <strong>run the connection to completion</strong> and return
 * only when it has ended; the transport closes the socket afterwards. Handlers
 * must not retain or close the streams beyond their own return.
 */
@NotNullByDefault
public interface ZtpConnectionHandler {

	/** Handles a connection this device dialled to {@code contactId}. */
	void handleOutgoing(TransportId transportId, int contactId, InputStream in,
			OutputStream out) throws IOException;

	/** Handles a connection the peer dialled to us (contact resolved via tag). */
	void handleIncoming(TransportId transportId, InputStream in,
			OutputStream out) throws IOException;
}
