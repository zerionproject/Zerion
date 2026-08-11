package org.zerionproject.core.api.plugin;

import org.zerionproject.core.api.plugin.duplex.DuplexPlugin;
import org.zerionproject.core.api.plugin.duplex.DuplexTransportConnection;
import org.zerionproject.core.api.plugin.simplex.SimplexPlugin;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface ConnectionHandler {

	void handleConnection(DuplexTransportConnection c);

	void handleReader(TransportConnectionReader r);

	void handleWriter(TransportConnectionWriter w);
}
