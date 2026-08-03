package org.zerionproject.app.conversation.voice;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import javax.annotation.Nullable;

@NotNullByDefault
public interface VoiceCallConnectionManager {

	class EndpointInfo {

		public final String onionAddress;

		public final int port;

		public EndpointInfo(String onionAddress, int port) {
			this.onionAddress = onionAddress;
			this.port = port;
		}
	}

	EndpointInfo createIncomingEndpoint(String callId, SecretKey voiceCallKey,
			boolean alice, VoiceCallConnectionHandler connectionHandler)
			throws IOException;

	@Nullable
	DuplexTransportConnection connectToRemote(String callId, String remoteOnion,
			SecretKey voiceCallKey, boolean alice) throws IOException;

	void closeEndpoint(String callId);

	void shutdown();
}
