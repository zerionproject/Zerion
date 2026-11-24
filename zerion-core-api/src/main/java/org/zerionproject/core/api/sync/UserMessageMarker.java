package org.zerionproject.core.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Records that a local message is one the user chose to send.
 * <p>
 * The cover traffic scheduler switches a connection to its active cadence
 * for two minutes after the first send of such a message. Every other local
 * message, in particular one a client creates automatically while handling a
 * record the peer sent, stays at the current cadence, so a contact cannot
 * change this side's sending rate by provoking traffic. A client that adds a
 * message on behalf of a user action calls this in the same database
 * transaction, right after it has added the message. Clients that do not
 * call it never open the window: the default is closed.
 */
@NotNullByDefault
public interface UserMessageMarker {

	void noteUserMessage(MessageId id);
}
