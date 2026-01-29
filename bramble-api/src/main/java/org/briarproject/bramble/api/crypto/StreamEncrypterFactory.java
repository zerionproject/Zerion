package org.briarproject.bramble.api.crypto;

import org.briarproject.bramble.api.transport.StreamContext;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.OutputStream;

@NotNullByDefault
public interface StreamEncrypterFactory {

	
	StreamEncrypter createStreamEncrypter(OutputStream out, StreamContext ctx);

	
	StreamEncrypter createContactExchangeStreamEncrypter(OutputStream out,
			SecretKey headerKey);

	
	StreamEncrypter createLogStreamEncrypter(OutputStream out,
			SecretKey headerKey);
}
