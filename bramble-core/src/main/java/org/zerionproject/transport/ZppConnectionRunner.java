package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

/**
 * Drives a live {@link ZwfDuplexConnection} to completion. This is the seam
 * between the transport (which produces authenticated duplex connections to a
 * contact) and the sync layer (which decides what to send and receive on them):
 * the pull protocol's constant-rate send/receive loop lives behind this
 * interface.
 *
 * <p>The implementation returns only when the connection has ended (the peer
 * closed it, an I/O error occurred, or the connection was told to stop). It must
 * not retain the connection after returning.
 */
@NotNullByDefault
public interface ZppConnectionRunner {

	/**
	 * Runs the sync loop over an established connection to {@code contactId}
	 * until it ends.
	 */
	void run(int contactId, ZwfDuplexConnection connection) throws IOException;
}
