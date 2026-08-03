package org.zerionproject.sync;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Tracks which contacts currently have a live connection and its send scheduler,
 * so the message layer can enqueue outgoing records for a contact that is online.
 * A contact with no registered scheduler is offline; its records wait until a
 * connection opens.
 */
@NotNullByDefault
public interface ZppConnectionRegistry {

	/**
	 * A connection to {@code contactId} has opened with this send scheduler.
	 * {@code maxRecordBytes} is the largest ZMM record the connection's frames
	 * carry, used to size records for fragmentation.
	 */
	void onConnectionOpened(int contactId, ZppSendScheduler scheduler,
			int maxRecordBytes);

	/**
	 * The connection with this {@code scheduler} has closed. The scheduler
	 * identifies the specific connection, so closing one connection to a contact
	 * never affects another live connection to the same contact.
	 */
	void onConnectionClosed(int contactId, ZppSendScheduler scheduler);
}
