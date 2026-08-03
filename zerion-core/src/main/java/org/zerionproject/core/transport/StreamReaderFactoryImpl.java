package org.zerionproject.core.transport;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.StreamDecrypterFactory;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.api.transport.StreamReaderFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

@Immutable
@NotNullByDefault
class StreamReaderFactoryImpl implements StreamReaderFactory {

	private final StreamDecrypterFactory streamDecrypterFactory;

	@Inject
	StreamReaderFactoryImpl(StreamDecrypterFactory streamDecrypterFactory) {
		this.streamDecrypterFactory = streamDecrypterFactory;
	}

	@Override
	public InputStream createStreamReader(InputStream in, StreamContext ctx) {
		return new StreamReaderImpl(streamDecrypterFactory
				.createStreamDecrypter(in, ctx));
	}

	@Override
	public InputStream createContactExchangeStreamReader(InputStream in,
			SecretKey headerKey) {
		return new StreamReaderImpl(streamDecrypterFactory
				.createContactExchangeStreamDecrypter(in, headerKey));
	}

	@Override
	public InputStream createLogStreamReader(InputStream in,
			SecretKey headerKey) {
		return new StreamReaderImpl(streamDecrypterFactory
				.createLogStreamDecrypter(in, headerKey));
	}
}
