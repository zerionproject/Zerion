package org.zerionproject.sync;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Receives the application records decoded from a connection's incoming frames.
 * Cover records are dropped by the pull loop and never reach here; every call is
 * a real record whose {@code type} is a {@link org.zerionproject.message.ZmmConstants}
 * value.
 */
@NotNullByDefault
public interface ZppRecordSink {

	/** Handles one decoded record received from {@code contactId}. */
	void deliver(int contactId, int type, byte[] payload);

	/**
	 * The connection to {@code contactId} has ended. Any records only partially
	 * reassembled for it are dropped, so a peer that disconnects mid-message does
	 * not leave fragments buffered until the process restarts.
	 */
	void onDisconnected(int contactId);
}
