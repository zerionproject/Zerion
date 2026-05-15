package org.briarproject.bramble.api.transport;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;

@NotNullByDefault
public interface StreamReaderFactory {

	InputStream createStreamReader(InputStream in, StreamContext ctx);

	InputStream createContactExchangeStreamReader(InputStream in,
			SecretKey headerKey);

	InputStream createLogStreamReader(InputStream in, SecretKey headerKey);
}
