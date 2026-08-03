package org.zerionproject.app.conversation.voice;

import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface VoiceCallConnectionHandler {

	void handleConnection(DuplexTransportConnection connection);
}
