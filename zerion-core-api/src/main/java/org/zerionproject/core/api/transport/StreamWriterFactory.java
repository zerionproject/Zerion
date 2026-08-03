package org.zerionproject.core.api.transport;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.OutputStream;

@NotNullByDefault
public interface StreamWriterFactory {

	StreamWriter createStreamWriter(OutputStream out, StreamContext ctx);

	StreamWriter createContactExchangeStreamWriter(OutputStream out,
			SecretKey headerKey);

	StreamWriter createLogStreamWriter(OutputStream out, SecretKey headerKey);
}
