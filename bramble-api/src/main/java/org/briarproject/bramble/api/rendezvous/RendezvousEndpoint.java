package org.briarproject.bramble.api.rendezvous;

import org.briarproject.bramble.api.properties.TransportProperties;

import java.io.Closeable;
import java.io.IOException;

public interface RendezvousEndpoint extends Closeable {

	TransportProperties getRemoteTransportProperties();

	@Override
	void close() throws IOException;
}
