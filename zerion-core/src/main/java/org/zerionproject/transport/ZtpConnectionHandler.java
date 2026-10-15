package org.zerionproject.transport;

import org.zerionproject.core.api.plugin.TransportId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import javax.annotation.Nullable;

@NotNullByDefault
public interface ZtpConnectionHandler {

	void handleOutgoing(TransportId transportId, int contactId, InputStream in,
			OutputStream out) throws IOException;

	default boolean handleOutgoingSession(TransportId transportId,
			int contactId, InputStream in, OutputStream out) {
		try {
			handleOutgoing(transportId, contactId, in, out);
		} catch (IOException e) {
		}
		return false;
	}

	default boolean handleOutgoingSession(TransportId transportId,
			int contactId, InputStream in, OutputStream out,
			boolean refusalExpected) {
		return handleOutgoingSession(transportId, contactId, in, out);
	}

	void handleIncoming(TransportId transportId, InputStream in,
			OutputStream out) throws IOException;

	default void handleIncoming(TransportId transportId, InputStream in,
			OutputStream out, boolean viaAuthorizedService)
			throws IOException {
		handleIncoming(transportId, in, out);
	}

	default void handleIncoming(TransportId transportId, InputStream in,
			OutputStream out, boolean viaAuthorizedService,
			@Nullable String localOnion) throws IOException {
		handleIncoming(transportId, in, out, viaAuthorizedService);
	}

	void handlePaired(TransportId transportId, int contactId, boolean incoming,
			InputStream in, OutputStream out) throws IOException;
}
