package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.DhRatchetState;
import org.zerionproject.core.api.crypto.pcs.PcsException;
import org.zerionproject.core.api.crypto.pcs.PcsRatchet;
import org.zerionproject.core.api.crypto.pcs.PcsSessionState;
import org.zerionproject.core.api.crypto.pcs.SkippedKeyStore;
import org.zerionproject.core.api.system.Clock;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.CHAIN_KEY_INPUT;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MAX_SKIP;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MESSAGE_KEY_INPUT;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_CHAIN_KEY_LABEL;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_DH_RATCHET_LABEL;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_DH_SECRET_LABEL;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_MESSAGE_KEY_LABEL;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_ROOT_KDF_LABEL;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_STREAM_CHAIN_LABEL;

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
	public SecretKey deriveStreamInitialChainKey(SecretKey rootKey,
			long streamNumber, byte[] salt) {
		return crypto.deriveKey(PCS_STREAM_CHAIN_LABEL, rootKey,
				streamNumberBytes(streamNumber), salt);
	}

	@Override
	public SecretKey deriveStreamInitialChainKey(SecretKey rootKey,
			long streamNumber) {
		return crypto.deriveKey(PCS_STREAM_CHAIN_LABEL, rootKey,
				streamNumberBytes(streamNumber));
	}

	private static byte[] streamNumberBytes(long streamNumber) {
		byte[] streamBytes = new byte[8];
		streamBytes[0] = (byte) (streamNumber >> 56);
		streamBytes[1] = (byte) (streamNumber >> 48);
		streamBytes[2] = (byte) (streamNumber >> 40);
		streamBytes[3] = (byte) (streamNumber >> 32);
		streamBytes[4] = (byte) (streamNumber >> 24);
		streamBytes[5] = (byte) (streamNumber >> 16);
		streamBytes[6] = (byte) (streamNumber >> 8);
		streamBytes[7] = (byte) streamNumber;
		return streamBytes;
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
				state.getDhState(),
				state.isMode3(),
				state.getPqEpoch(),
				state.getMode3FullState()
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
		KdfRkResult kdfResult;
		try {
			kdfResult = kdfRk(rootKey, dhOutput);
		} finally {
			java.util.Arrays.fill(dhOutput, (byte) 0);
		}
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
		KdfRkResult kdfResult1;
		try {
			kdfResult1 = kdfRk(rootKey, dhOutput1);
		} finally {
			java.util.Arrays.fill(dhOutput1, (byte) 0);
		}

		KeyPair newKeyPair = generateDhKeyPair();

		byte[] dhOutput2 = computeDh(newKeyPair, theirNewPublicKey);
		KdfRkResult kdfResult2;
		try {
			kdfResult2 = kdfRk(kdfResult1.getNewRootKey(), dhOutput2);
		} finally {
			java.util.Arrays.fill(dhOutput2, (byte) 0);
		}

		DhRatchetState newDhState = new DhRatchetState(newKeyPair, theirNewPublicKey);

		PcsSessionState newState = new PcsSessionState(
				kdfResult1.getChainKey(),
				0,
				state.getMessageNumber(),
				kdfResult2.getNewRootKey(),
				newDhState,
				state.isMode3(),
				state.getPqEpoch(),
				state.getMode3FullState()
		);

		return new DhRatchetResult(newState, newKeyPair.getPublic());
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
		try {
			byte[] inner = sharedSecret.getBytes();
			byte[] copy = inner.clone();
			return copy;
		} finally {
			sharedSecret.clear();
		}
	}

	private byte[] createChainId(PcsSessionState state) {
		DhRatchetState dhState = state.getDhState();
		if (dhState != null) {
			PublicKey remoteKey = dhState.getDhRemotePublicKey();
			if (remoteKey != null) {
				return crypto.hash(
						"org.zerionproject/PCS_CHAIN_ID",
						state.getChainKey().getBytes(),
						remoteKey.getEncoded()
				);
			}
		}
		return crypto.hash(
				"org.zerionproject/PCS_CHAIN_ID",
				state.getChainKey().getBytes()
		);
	}
}
