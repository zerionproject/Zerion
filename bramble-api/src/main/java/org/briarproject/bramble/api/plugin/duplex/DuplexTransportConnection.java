package org.briarproject.bramble.api.plugin.duplex;

import org.briarproject.bramble.api.plugin.TransportConnectionReader;
import org.briarproject.bramble.api.plugin.TransportConnectionWriter;
import org.briarproject.bramble.api.properties.TransportProperties;
import org.briarproject.nullsafety.NotNullByDefault;


@NotNullByDefault
public interface DuplexTransportConnection {

	
	TransportConnectionReader getReader();

	
	TransportConnectionWriter getWriter();

	
	TransportProperties getRemoteProperties();
}
