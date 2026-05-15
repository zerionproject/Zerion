package org.briarproject.briar.conversation.voice;

import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface VoiceCallConnectionHandler {

	void handleConnection(DuplexTransportConnection connection);
}
