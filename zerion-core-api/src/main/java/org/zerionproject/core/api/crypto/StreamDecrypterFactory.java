package org.zerionproject.core.api.crypto;

import org.zerionproject.core.api.transport.StreamContext;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;

@NotNullByDefault
public interface StreamDecrypterFactory {

	StreamDecrypter createStreamDecrypter(InputStream in, StreamContext ctx);

	StreamDecrypter createContactExchangeStreamDecrypter(InputStream in,
			SecretKey headerKey);

	StreamDecrypter createLogStreamDecrypter(InputStream in,
			SecretKey headerKey);
}
