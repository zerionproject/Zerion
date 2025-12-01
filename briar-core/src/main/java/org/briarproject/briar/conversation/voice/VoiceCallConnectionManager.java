package org.briarproject.briar.conversation.voice;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import javax.annotation.Nullable;

/**
 * Manages Tor hidden service connections for voice calls using
 * Briar's rendezvous endpoint infrastructure.
 * <p>
 * This manager handles the complete lifecycle of voice call connections:
 * - Creating temporary Tor hidden services for incoming calls
 * - Connecting to remote hidden services for outgoing calls
 * - Cleaning up resources when calls end
 * <p>
 * Thread-safe: All methods can be called from any thread.
 */
@NotNullByDefault
public interface VoiceCallConnectionManager {

	/**
	 * Information about a voice call endpoint for the callee (receiver).
	 * <p>
	 * This contains the onion address that the callee is listening on,
	 * which should be sent to the caller in the CALL_ANSWER message.
	 */
	class EndpointInfo {
		/** The v3 onion address (without .onion suffix) */
		public final String onionAddress;
		/** The port number (always 80 for Tor hidden services) */
		public final int port;

		public EndpointInfo(String onionAddress, int port) {
			this.onionAddress = onionAddress;
			this.port = port;
		}
	}

	/**
	 * Creates a rendezvous endpoint for an incoming call (callee side).
	 * <p>
	 * This creates a temporary Tor v3 hidden service that the caller can
	 * connect to. The hidden service is automatically registered with Tor
	 * and will accept incoming connections.
	 * <p>
	 * The caller should:
	 * 1. Generate a voice call key (or receive it from CALL_OFFER)
	 * 2. Call this method to create the endpoint
	 * 3. Get the onion address from the returned EndpointInfo
	 * 4. Send the onion address to the remote peer in CALL_ANSWER
	 * 5. Wait for incoming connection (handled by ConnectionHandler)
	 *
	 * @param callId Unique identifier for this call
	 * @param voiceCallKey The shared secret key for this call
	 * @param alice True if this party is Alice (affects key derivation)
	 * @param connectionHandler Handler that will be called when connection arrives
	 * @return Information about the created endpoint (onion address and port)
	 * @throws IOException if the endpoint cannot be created
	 */
	EndpointInfo createIncomingEndpoint(String callId, SecretKey voiceCallKey,
			boolean alice, VoiceCallConnectionHandler connectionHandler)
			throws IOException;

	/**
	 * Connects to a remote rendezvous endpoint for an outgoing call (caller side).
	 * <p>
	 * This connects to the callee's Tor hidden service using Briar's
	 * TorPlugin infrastructure. The connection goes through Tor circuits
	 * and provides end-to-end anonymity.
	 * <p>
	 * The caller should:
	 * 1. Receive the remote onion address from CALL_ANSWER
	 * 2. Call this method to connect
	 * 3. Use the returned DuplexTransportConnection for audio streaming
	 *
	 * @param callId Unique identifier for this call
	 * @param remoteOnion The remote party's onion address (from CALL_ANSWER)
	 * @param voiceCallKey The shared secret key for this call
	 * @param alice True if this party is Alice (affects key derivation)
	 * @return A duplex connection for bidirectional audio streaming
	 * @throws IOException if connection fails
	 */
	@Nullable
	DuplexTransportConnection connectToRemote(String callId, String remoteOnion,
			SecretKey voiceCallKey, boolean alice) throws IOException;

	/**
	 * Closes a voice call endpoint and releases all resources.
	 * <p>
	 * This removes the Tor hidden service, closes any open connections,
	 * and cleans up all associated resources. Should be called when
	 * the call ends, either normally or due to an error.
	 * <p>
	 * Safe to call multiple times - subsequent calls are no-ops.
	 *
	 * @param callId The call ID whose endpoint should be closed
	 */
	void closeEndpoint(String callId);
}
