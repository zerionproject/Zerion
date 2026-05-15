package org.briarproject.bramble.api.plugin;

import org.briarproject.bramble.api.plugin.duplex.DuplexPlugin;
import org.briarproject.bramble.api.plugin.duplex.DuplexTransportConnection;
import org.briarproject.bramble.api.plugin.simplex.SimplexPlugin;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ConnectionHandler {

	void handleConnection(DuplexTransportConnection c);

	void handleReader(TransportConnectionReader r);

	void handleWriter(TransportConnectionWriter w);
}
