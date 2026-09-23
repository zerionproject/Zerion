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
 * recognised, so the handler resolves the contact itself. A socket on which a
 * pairing has just completed is a third case: the contact is known on both
 * sides, so the first session runs on it without a tag lookup.
 *
 * <p>All methods <strong>run the connection to completion</strong> and return
 * only when it has ended; the caller closes the socket afterwards. Handlers
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

	/**
	 * Handles the socket on which {@code contactId} was just paired. The
	 * pairing exchange committed the contact's session inputs on both sides
	 * before returning, so the same socket carries the first resumed session.
	 * {@code incoming} says whether the peer dialled this socket, which only
	 * decides how the connection is registered.
	 */
	void handlePaired(TransportId transportId, int contactId, boolean incoming,
			InputStream in, OutputStream out) throws IOException;
}
