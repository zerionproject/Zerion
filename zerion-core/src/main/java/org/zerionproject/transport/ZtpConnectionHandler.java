package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.TransportId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

@NotNullByDefault
public interface ZtpConnectionHandler {

	void handleOutgoing(TransportId transportId, int contactId, InputStream in,
			OutputStream out) throws IOException;

	void handleIncoming(TransportId transportId, InputStream in,
			OutputStream out) throws IOException;

	default void handleIncoming(TransportId transportId, InputStream in,
			OutputStream out, boolean viaAuthorizedService)
			throws IOException {
		handleIncoming(transportId, in, out);
	}

	void handlePaired(TransportId transportId, int contactId, boolean incoming,
			InputStream in, OutputStream out) throws IOException;
}
