package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.StreamEncrypter;
import org.briarproject.bramble.api.crypto.StreamEncrypterFactory;
import org.briarproject.bramble.api.crypto.TransportCrypto;
import org.briarproject.bramble.api.crypto.pcs.Mode3FullRatchet;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet;
import org.briarproject.bramble.api.crypto.pcs.PcsSessionState;
import org.briarproject.bramble.api.crypto.pcs.PqRatchet;
import org.briarproject.bramble.api.crypto.pcs.PqRatchetState;
import org.briarproject.bramble.api.transport.StreamContext;
import org.briarproject.bramble.crypto.pcs.PcsStateManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.OutputStream;
import java.util.function.Consumer;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import javax.inject.Provider;

import static org.briarproject.bramble.api.transport.TransportConstants.PROTOCOL_VERSION;
import static org.briarproject.bramble.api.transport.TransportConstants.STREAM_HEADER_NONCE_LENGTH;
import static org.briarproject.bramble.api.transport.TransportConstants.TAG_LENGTH;

@Immutable
@NotNullByDefault
class StreamEncrypterFactoryImpl implements StreamEncrypterFactory {

	private final CryptoComponent crypto;
	private final TransportCrypto transportCrypto;
	private final Provider<AuthenticatedCipher> cipherProvider;
	private final PcsRatchet pcsRatchet;
	private final PqRatchet pqRatchet;
	private final PcsStateManager pcsStateManager;
	private final Mode3FullRatchet mode3FullRatchet;

	@Inject
	StreamEncrypterFactoryImpl(CryptoComponent crypto,
			TransportCrypto transportCrypto,
			Provider<AuthenticatedCipher> cipherProvider,
			PcsRatchet pcsRatchet,
			PqRatchet pqRatchet,
			PcsStateManager pcsStateManager,
			Mode3FullRatchet mode3FullRatchet) {
		this.crypto = crypto;
		this.transportCrypto = transportCrypto;
		this.cipherProvider = cipherProvider;
		this.pcsRatchet = pcsRatchet;
		this.pqRatchet = pqRatchet;
		this.pcsStateManager = pcsStateManager;
		this.mode3FullRatchet = mode3FullRatchet;
	}

	@Override
	public StreamEncrypter createStreamEncrypter(OutputStream out,
			StreamContext ctx) {
		AuthenticatedCipher cipher = cipherProvider.get();
		long streamNumber = ctx.getStreamNumber();
		byte[] tag = new byte[TAG_LENGTH];
		transportCrypto.encodeTag(tag, ctx.getTagKey(), PROTOCOL_VERSION,
				streamNumber);
		byte[] streamHeaderNonce = new byte[STREAM_HEADER_NONCE_LENGTH];
		crypto.getSecureRandom().nextBytes(streamHeaderNonce);

		if (!ctx.isPcsEnabled()) {
			SecretKey frameKey = crypto.generateSecretKey();
			return new StreamEncrypterImpl(out, cipher, streamNumber, tag,
					streamHeaderNonce, ctx.getHeaderKey(), frameKey);
		}

		PcsSessionState pcsState = ctx.getPcsState();
		if (pcsState == null) {
			throw new IllegalStateException("PCS enabled but no state");
		}

		PqRatchetState pqState = ctx.getPqRatchetState();
		boolean isMode3 = pcsState.isMode3() && pqState != null;

		ContactId contactId = ctx.getContactId();
		Consumer<PcsSessionState> sendStateCallback = contactId == null
				? null
				: s -> pcsStateManager.saveSendState(contactId, s);
		Consumer<PqRatchetState> pqCallback = contactId == null
				? null
				: s -> pcsStateManager.savePqState(contactId, s);
		Consumer<SecretKey> pqCrossMix = contactId == null
				? null
				: pqSecret -> pcsStateManager
						.mixPqSecretIntoReceiveRoot(contactId, pqSecret,
								pqRatchet);

		if (isMode3) {
			return new PcsStreamEncrypterImpl(out, cipher, pcsRatchet,
					streamNumber, tag, streamHeaderNonce, ctx.getHeaderKey(),
					pcsState, sendStateCallback, pqRatchet, pqState,
					pqCallback, pqCrossMix, mode3FullRatchet);
		}

		return new PcsStreamEncrypterImpl(out, cipher, pcsRatchet,
				streamNumber, tag, streamHeaderNonce, ctx.getHeaderKey(),
				pcsState, sendStateCallback);
	}

	@Override
	public StreamEncrypter createContactExchangeStreamEncrypter(
			OutputStream out, SecretKey headerKey) {
		AuthenticatedCipher cipher = cipherProvider.get();
		byte[] streamHeaderNonce = new byte[STREAM_HEADER_NONCE_LENGTH];
		crypto.getSecureRandom().nextBytes(streamHeaderNonce);
		SecretKey frameKey = crypto.generateSecretKey();
		return new StreamEncrypterImpl(out, cipher, 0, null, streamHeaderNonce,
				headerKey, frameKey);
	}

	@Override
	public StreamEncrypter createLogStreamEncrypter(OutputStream out,
			SecretKey headerKey) {
		return createContactExchangeStreamEncrypter(out, headerKey);
	}
}
