package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.pcs.DhRatchetState;
import org.briarproject.bramble.api.crypto.pcs.PcsException;
import org.briarproject.bramble.api.crypto.pcs.PcsRatchet;
import org.briarproject.bramble.api.crypto.pcs.PcsSessionState;
import org.briarproject.bramble.api.crypto.pcs.SkippedKeyStore;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.CHAIN_KEY_INPUT;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MAX_SKIP;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MESSAGE_KEY_INPUT;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_CHAIN_KEY_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_DH_RATCHET_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_DH_SECRET_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_MESSAGE_KEY_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_ROOT_KDF_LABEL;

@Immutable
@NotNullByDefault
public class PcsRatchetImpl implements PcsRatchet {

	private final CryptoComponent crypto;
	private final Clock clock;

	@Inject
	public PcsRatchetImpl(CryptoComponent crypto, Clock clock) {
		this.crypto = crypto;
		this.clock = clock;
	}

	@Override
	public SecretKey derivePcsRootKey(SecretKey contactRootKey) {
		return crypto.deriveKey(PCS_ROOT_KDF_LABEL, contactRootKey);
	}

	@Override
	public KdfCkResult kdfCk(SecretKey chainKey) {
		SecretKey newChainKey = crypto.deriveKey(
				PCS_CHAIN_KEY_LABEL,
				chainKey,
				new byte[]{CHAIN_KEY_INPUT}
		);

		SecretKey messageKey = crypto.deriveKey(
				PCS_MESSAGE_KEY_LABEL,
				chainKey,
				new byte[]{MESSAGE_KEY_INPUT}
		);

		return new KdfCkResult(newChainKey, messageKey);
	}

	@Override
	public AdvanceResult advanceSendChain(PcsSessionState state) {
		KdfCkResult kdfResult = kdfCk(state.getChainKey());
		PcsSessionState newState = state.advance(kdfResult.getNewChainKey());
		return new AdvanceResult(kdfResult.getMessageKey(), newState);
	}

	@Override
	public AdvanceResult advanceReceiveChain(PcsSessionState state,
			int messageNumber, SkippedKeyStore skippedKeyStore)
			throws PcsException {

		int currentNumber = state.getMessageNumber();

		if (messageNumber < currentNumber) {
			throw new PcsException(
					"Message number " + messageNumber + " is in the past " +
					"(current: " + currentNumber + ")");
		}

		int skip = messageNumber - currentNumber;
		if (skip > MAX_SKIP) {
			throw new PcsException(
					"Message number " + messageNumber + " is too far ahead " +
					"(skip: " + skip + ", max: " + MAX_SKIP + ")");
		}

		SecretKey currentChainKey = state.getChainKey();
		long now = clock.currentTimeMillis();

		for (int i = currentNumber; i < messageNumber; i++) {
			KdfCkResult kdfResult = kdfCk(currentChainKey);
			byte[] chainId = createChainId(state);
			skippedKeyStore.storeSkippedKey(chainId, i, kdfResult.getMessageKey(), now);
			currentChainKey = kdfResult.getNewChainKey();
		}

		KdfCkResult finalKdf = kdfCk(currentChainKey);

		PcsSessionState newState = new PcsSessionState(
				finalKdf.getNewChainKey(),
				messageNumber + 1,
				state.getPreviousChainLength(),
				state.getRootKey(),
				state.getDhState()
		);

		return new AdvanceResult(finalKdf.getMessageKey(), newState);
	}

	@Override
	public KdfRkResult kdfRk(SecretKey rootKey, byte[] dhOutput) {
		SecretKey newRootKey = crypto.deriveKey(
				PCS_DH_RATCHET_LABEL,
				rootKey,
				dhOutput,
				new byte[]{0x01}
		);

		SecretKey chainKey = crypto.deriveKey(
				PCS_DH_RATCHET_LABEL,
				rootKey,
				dhOutput,
				new byte[]{0x02}
		);

		return new KdfRkResult(newRootKey, chainKey);
	}

	@Override
	public KeyPair generateDhKeyPair() {
		return crypto.generateAgreementKeyPair();
	}

	@Override
	public DhRatchetResult performSendDhRatchet(PcsSessionState state)
			throws GeneralSecurityException, PcsException {

		if (!state.isMode2()) {
			throw new PcsException("State is not Mode 2");
		}

		DhRatchetState dhState = state.getDhState();
		if (dhState == null) {
			throw new PcsException("DH state is null");
		}

		SecretKey rootKey = state.getRootKey();
		if (rootKey == null) {
			throw new PcsException("Root key is null");
		}

		if (!dhState.hasRemotePublicKey()) {
			return new DhRatchetResult(state, dhState.getDhPublicKey());
		}

		byte[] dhOutput = computeDh(dhState.getDhKeyPair(), dhState.getDhRemotePublicKey());
		KdfRkResult kdfResult = kdfRk(rootKey, dhOutput);
		KeyPair newKeyPair = generateDhKeyPair();
		DhRatchetState newDhState = dhState.withNewKeyPair(newKeyPair);

		PcsSessionState newState = state.afterDhRatchet(
				kdfResult.getNewRootKey(),
				kdfResult.getChainKey(),
				newDhState
		);

		return new DhRatchetResult(newState, newKeyPair.getPublic());
	}

	@Override
	public DhRatchetResult performReceiveDhRatchet(PcsSessionState state,
			PublicKey theirNewPublicKey)
			throws GeneralSecurityException, PcsException {

		if (!state.isMode2()) {
			throw new PcsException("State is not Mode 2");
		}

		DhRatchetState dhState = state.getDhState();
		if (dhState == null) {
			throw new PcsException("DH state is null");
		}

		SecretKey rootKey = state.getRootKey();
		if (rootKey == null) {
			throw new PcsException("Root key is null");
		}

		byte[] dhOutput1 = computeDh(dhState.getDhKeyPair(), theirNewPublicKey);
		KdfRkResult kdfResult1 = kdfRk(rootKey, dhOutput1);

		KeyPair newKeyPair = generateDhKeyPair();

		byte[] dhOutput2 = computeDh(newKeyPair, theirNewPublicKey);
		KdfRkResult kdfResult2 = kdfRk(kdfResult1.getNewRootKey(), dhOutput2);

		DhRatchetState newDhState = new DhRatchetState(newKeyPair, theirNewPublicKey);

		PcsSessionState newState = new PcsSessionState(
				kdfResult1.getChainKey(),
				0,
				state.getMessageNumber(),
				kdfResult2.getNewRootKey(),
				newDhState
		);

		return new DhRatchetResult(newState, newKeyPair.getPublic());
	}

	@Override
	public PcsSessionState initializeMode2(PcsSessionState mode1State) {
		KeyPair dhKeyPair = generateDhKeyPair();
		DhRatchetState dhState = new DhRatchetState(dhKeyPair, null);
		return PcsSessionState.createInitialMode2(
				mode1State.getChainKey(),
				mode1State.getChainKey(),
				dhState
		);
	}

	@Override
	public PcsSessionState initializeMode2AsInitiator(SecretKey rootKey) {
		KeyPair dhKeyPair = generateDhKeyPair();
		DhRatchetState dhState = new DhRatchetState(dhKeyPair, null);
		return PcsSessionState.createInitialMode2(rootKey, rootKey, dhState);
	}

	@Override
	public PcsSessionState initializeMode2AsResponder(SecretKey rootKey,
			PublicKey theirPublicKey) throws GeneralSecurityException {
		KeyPair dhKeyPair = generateDhKeyPair();
		DhRatchetState dhState = new DhRatchetState(dhKeyPair, theirPublicKey);
		return PcsSessionState.createInitialMode2(rootKey, rootKey, dhState);
	}

	private byte[] computeDh(KeyPair ourKeyPair, PublicKey theirPublicKey)
			throws GeneralSecurityException {
		SecretKey sharedSecret = crypto.deriveSharedSecret(
				PCS_DH_SECRET_LABEL,
				theirPublicKey,
				ourKeyPair
		);
		return sharedSecret.getBytes();
	}

	private byte[] createChainId(PcsSessionState state) {
		DhRatchetState dhState = state.getDhState();
		if (dhState != null) {
			PublicKey remoteKey = dhState.getDhRemotePublicKey();
			if (remoteKey != null) {
				return crypto.hash(
						"org.briarproject.zerion/PCS_CHAIN_ID",
						state.getChainKey().getBytes(),
						remoteKey.getEncoded()
				);
			}
		}
		return crypto.hash(
				"org.briarproject.zerion/PCS_CHAIN_ID",
				state.getChainKey().getBytes()
		);
	}
}
