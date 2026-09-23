package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.StreamDecrypter;
import org.zerionproject.core.api.crypto.StreamDecrypterFactory;
import org.zerionproject.core.api.transport.StreamContext;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Builds the classical stream decrypter that carries pairing handshakes and
 * contact exchanges. A context that carries ratchet state is refused, for the
 * reason given on {@link StreamEncrypterFactoryImpl}.
 */
@Immutable
@NotNullByDefault
class StreamDecrypterFactoryImpl implements StreamDecrypterFactory {

	private final Provider<AuthenticatedCipher> cipherProvider;

	@Inject
	StreamDecrypterFactoryImpl(Provider<AuthenticatedCipher> cipherProvider) {
		this.cipherProvider = cipherProvider;
	}

	@Override
	public StreamDecrypter createStreamDecrypter(InputStream in,
			StreamContext ctx) {
		if (ctx.isPcsEnabled()) {
			throw new IllegalStateException(
					"Ratchet streams are not carried over rotation keys");
		}
		return new StreamDecrypterImpl(in, cipherProvider.get(),
				ctx.getStreamNumber(), ctx.getHeaderKey());
	}

	@Override
	public StreamDecrypter createContactExchangeStreamDecrypter(InputStream in,
			SecretKey headerKey) {
		return new StreamDecrypterImpl(in, cipherProvider.get(), 0, headerKey);
	}

	@Override
	public StreamDecrypter createLogStreamDecrypter(InputStream in,
			SecretKey headerKey) {
		return createContactExchangeStreamDecrypter(in, headerKey);
	}
}
