package org.zerionproject.core.crypto;

import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.StreamDecrypter;
import org.zerionproject.core.api.crypto.StreamDecrypterFactory;
import org.zerionproject.core.api.crypto.pcs.Mode3FullRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.crypto.pcs.PqRatchet;
import org.zerionproject.core.api.crypto.pcs.PqRatchetState;
import org.zerionproject.core.api.crypto.pcs.SkippedKeyStore;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.crypto.pcs.DatabaseSkippedKeyStore;
import org.zerionproject.core.crypto.pcs.PcsStateManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.InputStream;
import java.util.function.Consumer;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import javax.inject.Provider;

@Immutable
@NotNullByDefault
class StreamDecrypterFactoryImpl implements StreamDecrypterFactory {

	private final Provider<AuthenticatedCipher> cipherProvider;
	private final PcsRatchet pcsRatchet;
	private final PqRatchet pqRatchet;
	private final SkippedKeyStore skippedKeyStore;
	private final PcsStateManager pcsStateManager;
	private final Mode3FullRatchet mode3FullRatchet;

	@Inject
	StreamDecrypterFactoryImpl(Provider<AuthenticatedCipher> cipherProvider,
			PcsRatchet pcsRatchet, PqRatchet pqRatchet,
			SkippedKeyStore skippedKeyStore,
			PcsStateManager pcsStateManager,
			Mode3FullRatchet mode3FullRatchet) {
		this.cipherProvider = cipherProvider;
		this.pcsRatchet = pcsRatchet;
		this.pqRatchet = pqRatchet;
		this.skippedKeyStore = skippedKeyStore;
		this.pcsStateManager = pcsStateManager;
		this.mode3FullRatchet = mode3FullRatchet;
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

		PqRatchetState pqState = ctx.getPqRatchetState();
		boolean isMode3Full = pcsState.isMode3Full();
		boolean isMode3 = pcsState.isMode3() && pqState != null;

		final ContactId cid = contactId;
		Consumer<PcsSessionState> recvStateCallback =
				s -> pcsStateManager.saveReceiveState(cid, s);
		Consumer<PqRatchetState> pqCallback =
				s -> pcsStateManager.savePqState(cid, s);
		Consumer<SecretKey> pqCrossMix = pqSecret -> pcsStateManager
				.mixPqSecretIntoSendRoot(cid, pqSecret, pqRatchet);
		java.util.function.Supplier<
				org.zerionproject.core.api.crypto.pcs.Mode3FullState>
				mode3FullRefresher =
				() -> pcsStateManager.loadSharedMode3FullState(cid);
		java.util.function.Supplier<PcsSessionState>
				sessionStateRefresher =
				() -> pcsStateManager.loadReceiveState(cid);
		java.util.concurrent.locks.Lock directionLock =
				pcsStateManager.getDirectionLock(cid,
						org.zerionproject.core.api.db.DatabaseComponent
								.PCS_DIRECTION_RECEIVE);

		if (isMode3Full || isMode3) {
			return new PcsStreamDecrypterImpl(in, cipher, pcsRatchet,
					skippedKeyStore, chainId, ctx.getStreamNumber(),
					ctx.getHeaderKey(), pcsState, recvStateCallback, null,
					pqRatchet, pqState, pqCallback, pqCrossMix,
					mode3FullRatchet, mode3FullRefresher,
					sessionStateRefresher, directionLock);
		}

		return new PcsStreamDecrypterImpl(in, cipher, pcsRatchet,
				skippedKeyStore, chainId, ctx.getStreamNumber(),
				ctx.getHeaderKey(), pcsState, recvStateCallback);
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
