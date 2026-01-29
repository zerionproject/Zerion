package org.briarproject.bramble.api.crypto;

import org.briarproject.bramble.api.transport.StreamContext;
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
