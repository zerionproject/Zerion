package org.zerionproject.core.api.rendezvous;

import org.zerionproject.core.api.properties.TransportProperties;

import java.io.Closeable;
import java.io.IOException;

public interface RendezvousEndpoint extends Closeable {

	TransportProperties getRemoteTransportProperties();

	@Override
	void close() throws IOException;
}
