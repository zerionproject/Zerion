package org.zerionproject.core.crypto;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.StreamEncrypter;
import org.zerionproject.core.api.crypto.StreamEncrypterFactory;
import org.zerionproject.core.api.crypto.TransportCrypto;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.crypto.pcs.PqRatchet;
import org.zerionproject.core.api.crypto.pcs.PqRatchetState;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.crypto.pcs.PcsStateManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.OutputStream;
import java.util.function.Consumer;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import javax.inject.Provider;

import static org.zerionproject.core.api.transport.TransportConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.api.transport.TransportConstants.STREAM_HEADER_NONCE_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.TAG_LENGTH;

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
		boolean isMode3Full = pcsState.isMode3Full();
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
		java.util.function.Supplier<
				org.zerionproject.core.api.crypto.pcs.Mode3FullState>
				mode3FullRefresher = contactId == null ? null
				: () -> pcsStateManager.loadSharedMode3FullState(contactId);
		java.util.function.Supplier<PcsSessionState>
				sessionStateRefresher = contactId == null ? null
				: () -> pcsStateManager.loadSendState(contactId);
		java.util.concurrent.locks.Lock directionLock = contactId == null
				? null
				: pcsStateManager.getDirectionLock(contactId,
						org.zerionproject.core.api.db.DatabaseComponent
								.PCS_DIRECTION_SEND);

		if (isMode3Full || isMode3) {
			return new PcsStreamEncrypterImpl(out, cipher, pcsRatchet,
					streamNumber, tag, streamHeaderNonce, ctx.getHeaderKey(),
					pcsState, sendStateCallback, pqRatchet, pqState,
					pqCallback, pqCrossMix, mode3FullRatchet,
					mode3FullRefresher, sessionStateRefresher, directionLock);
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
