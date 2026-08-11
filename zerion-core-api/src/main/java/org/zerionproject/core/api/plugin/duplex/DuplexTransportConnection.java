package org.zerionproject.core.api.plugin.duplex;

import org.zerionproject.core.api.plugin.TransportConnectionReader;
import org.zerionproject.core.api.plugin.TransportConnectionWriter;
import org.zerionproject.core.api.properties.TransportProperties;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface DuplexTransportConnection {

	TransportConnectionReader getReader();

	TransportConnectionWriter getWriter();

	TransportProperties getRemoteProperties();
}
