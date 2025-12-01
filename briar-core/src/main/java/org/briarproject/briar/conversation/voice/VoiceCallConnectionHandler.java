package org.briarproject.briar.conversation.voice;

import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Handler for incoming voice call connections.
 * <p>
 * This is called when a remote peer connects to our Tor hidden service
 * for a voice call. The handler receives the connection and can start
 * audio streaming.
 */
@NotNullByDefault
public interface VoiceCallConnectionHandler {

	/**
	 * Called when an incoming voice call connection is established.
	 * <p>
	 * This method is called on a background thread. The handler should:
	 * 1. Verify the connection is for the expected call
	 * 2. Start audio streaming using the provided connection
	 * 3. Update the UI to reflect the connected state
	 *
	 * @param connection The established duplex transport connection
	 */
	void handleConnection(DuplexTransportConnection connection);
}
