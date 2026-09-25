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

	/** A connection to {@code contactId} has opened. */
	default void onConnected(int contactId) {
	}

	/**
	 * A connection to {@code contactId} has ended. Once the contact's last
	 * connection has ended, any records only partially reassembled for it are
	 * dropped, so a peer that disconnects mid-message does not leave fragments
	 * buffered until the process restarts; while another connection to the
	 * same contact is still open its partial records are kept.
	 */
	void onDisconnected(int contactId);
}
