package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.StreamDecrypter;
import org.briarproject.bramble.api.crypto.StreamDecrypterFactory;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet;
import org.briarproject.bramble.api.crypto.pcs.PcsSessionState;
import org.briarproject.bramble.api.crypto.pcs.PqRatchetState;
import org.briarproject.bramble.api.crypto.pcs.SkippedKeyStore;
import org.briarproject.bramble.api.transport.StreamContext;
import org.briarproject.bramble.crypto.pcs.DatabaseSkippedKeyStore;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import javax.inject.Provider;

@Immutable
@NotNullByDefault
class StreamDecrypterFactoryImpl implements StreamDecrypterFactory {

	private final Provider<AuthenticatedCipher> cipherProvider;
	private final PcsRatchet pcsRatchet;
	private final SkippedKeyStore skippedKeyStore;

	@Inject
	StreamDecrypterFactoryImpl(Provider<AuthenticatedCipher> cipherProvider,
			PcsRatchet pcsRatchet, SkippedKeyStore skippedKeyStore) {
		this.cipherProvider = cipherProvider;
		this.pcsRatchet = pcsRatchet;
		this.skippedKeyStore = skippedKeyStore;
	}

	@Override
	public StreamDecrypter createStreamDecrypter(InputStream in,
			StreamContext ctx) {
		AuthenticatedCipher cipher = cipherProvider.get();

		if (!ctx.isPcsEnabled()) {
			return new StreamDecrypterImpl(in, cipher, ctx.getStreamNumber(),
					ctx.getHeaderKey());
		}

		PcsSessionState pcsState = ctx.getPcsState();
		ContactId contactId = ctx.getContactId();
		if (pcsState == null || contactId == null) {
			throw new IllegalStateException("PCS enabled but no state or contact");
		}

		byte[] chainId = DatabaseSkippedKeyStore.createChainId(contactId, false);

		return new PcsStreamDecrypterImpl(in, cipher, pcsRatchet,
				skippedKeyStore, chainId, ctx.getStreamNumber(),
				ctx.getHeaderKey(), pcsState, null);
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
