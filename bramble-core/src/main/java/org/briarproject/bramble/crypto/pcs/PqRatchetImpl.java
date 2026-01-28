package org.briarproject.bramble.crypto.pcs;

import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.crypto.pcs.MlKemEncapsulation;
import org.briarproject.bramble.api.crypto.pcs.MlKemKeyPair;
import org.briarproject.bramble.api.crypto.pcs.MlKemProvider;
import org.briarproject.bramble.api.crypto.pcs.PcsException;
import org.briarproject.bramble.api.crypto.pcs.PqChunk;
import org.briarproject.bramble.api.crypto.pcs.PqEpochState;
import org.briarproject.bramble.api.crypto.pcs.PqRatchet;
import org.briarproject.bramble.api.crypto.pcs.PqRatchetState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_CIPHERTEXT_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.MLKEM_EK_VECTOR_SIZE;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_HYBRID_CHAIN_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_HYBRID_ROOT_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PCS_PQ_ROOT_UPDATE_LABEL;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PQ_CIPHERTEXT_CHUNKS;
import static org.briarproject.bramble.api.crypto.pcs.PcsConstants.PQ_EK_VECTOR_CHUNKS;

@NotNullByDefault
class PqRatchetImpl implements PqRatchet {

	private final CryptoComponent crypto;
	private final MlKemProvider mlKemProvider;
	private final ChunkingManager chunkingManager;

	@Inject
	PqRatchetImpl(CryptoComponent crypto, MlKemProvider mlKemProvider,
			ChunkingManager chunkingManager) {
		this.crypto = crypto;
		this.mlKemProvider = mlKemProvider;
		this.chunkingManager = chunkingManager;
	}

	@Override
	public PqRatchetState initialize(long currentTime) {
		return PqRatchetState.createReady(currentTime);
	}

	@Override
	public PqRatchetState startEpochAsInitiator(PqRatchetState state) {
		MlKemKeyPair keyPair = mlKemProvider.generateKeyPair();
		return state.startInitiatorEpoch(keyPair);
	}

	@Override
	public PqRatchetState receiveEkSeed(PqRatchetState state, byte[] ekSeed,
			byte[] ekHash) {
		return state.startResponderEpoch(ekSeed, ekHash);
	}

	@Override
	@Nullable
	public PqChunk getNextChunkToSend(PqRatchetState state) {
		return chunkingManager.getNextChunkToSend(state);
	}

	@Override
	public PqRatchetState processChunkSent(PqRatchetState state) {
		PqRatchetState updated = state.withChunkSent();

		switch (state.getState()) {
			case PQ_SENDING_EK_SEED:
				return updated.withState(PqEpochState.PQ_SENDING_EK_VEC)
						.withChunkSent();

			case PQ_SENDING_EK_VEC:
				if (chunkingManager.isEkVectorSendComplete(updated)) {
					return updated.withState(PqEpochState.PQ_AWAITING_CT);
				}
				return updated;

			case PQ_SENDING_CT:
				if (chunkingManager.isCiphertextSendComplete(updated)) {
					return updated.withState(PqEpochState.PQ_COMPLETE);
				}
				return updated;

			default:
				return updated;
		}
	}

	@Override
	public PqRatchetState processChunkReceived(PqRatchetState state,
			PqChunk chunk) {
		byte[] pending = state.getPendingChunks();
		if (pending == null) pending = new byte[0];

		byte[] newPending = chunkingManager.appendChunk(pending,
				chunk.getData());
		PqRatchetState updated = state.withChunkReceived(newPending);

		switch (state.getState()) {
			case PQ_RECEIVING_EK_VEC:
				if (updated.getChunksReceived() >= PQ_EK_VECTOR_CHUNKS) {
					byte[] ekVector = chunkingManager.assembleEkVector(
							newPending, PQ_EK_VECTOR_CHUNKS);
					if (ekVector != null) {
						byte[] ekSeed = state.getTheirEkSeed();
						byte[] ekHash = state.getTheirEkHash();
						if (ekSeed != null && ekHash != null) {
							if (mlKemProvider.verifyEkHash(ekSeed, ekVector,
									ekHash)) {
								byte[] fullEk = assembleEncapsulationKey(
										ekSeed, ekVector);
								MlKemEncapsulation enc =
										mlKemProvider.encapsulate(fullEk);
								return updated
										.withEkVector(ekVector)
										.withCiphertext(enc.getCiphertext())
										.withState(PqEpochState.PQ_SENDING_CT);
							}
						}
					}
					return state.reset();
				}
				return updated;

			case PQ_AWAITING_CT:
				if (updated.getChunksReceived() >= PQ_CIPHERTEXT_CHUNKS) {
					byte[] ct = chunkingManager.assembleCiphertext(
							newPending, PQ_CIPHERTEXT_CHUNKS);
					if (ct != null) {
						return updated
								.withCiphertext(ct)
								.withState(PqEpochState.PQ_COMPLETE);
					}
					return state.reset();
				}
				return updated;

			default:
				return updated;
		}
	}

	@Override
	public boolean isEpochComplete(PqRatchetState state) {
		return state.getState() == PqEpochState.PQ_COMPLETE;
	}

	@Override
	public SecretKey deriveEpochSecret(PqRatchetState state)
			throws PcsException {
		if (state.getState() != PqEpochState.PQ_COMPLETE) {
			throw new PcsException("Epoch not complete");
		}

		byte[] sharedSecret;

		if (state.isInitiator()) {
			MlKemKeyPair keyPair = state.getOurKeyPair();
			byte[] ciphertext = state.getCiphertext();
			if (keyPair == null || ciphertext == null) {
				throw new PcsException("Missing key material");
			}
			sharedSecret = mlKemProvider.decapsulate(
					keyPair.getDecapsulationKey(), ciphertext);
		} else {
			byte[] ekSeed = state.getTheirEkSeed();
			byte[] ekVector = state.getTheirEkVector();
			byte[] ciphertext = state.getCiphertext();
			if (ekSeed == null || ekVector == null || ciphertext == null) {
				throw new PcsException("Missing key material");
			}
			byte[] fullEk = assembleEncapsulationKey(ekSeed, ekVector);
			MlKemEncapsulation enc = mlKemProvider.encapsulate(fullEk);
			if (!Arrays.equals(enc.getCiphertext(), ciphertext)) {
				throw new PcsException("Ciphertext mismatch");
			}
			sharedSecret = enc.getSharedSecret();
		}

		return new SecretKey(sharedSecret);
	}

	@Override
	public PqRatchetState completeEpoch(PqRatchetState state, long currentTime) {
		return state.completeEpoch(currentTime);
	}

	@Override
	public PqRatchetState incrementMessageCount(PqRatchetState state) {
		return state.incrementMessageCount();
	}

	@Override
	public boolean shouldStartNewEpoch(PqRatchetState state, long currentTime) {
		return state.shouldStartNewEpoch(currentTime);
	}

	@Override
	public SecretKey mixPqSecretIntoRootKey(SecretKey rootKey,
			SecretKey pqSecret) {
		return crypto.deriveKey(PCS_PQ_ROOT_UPDATE_LABEL, rootKey,
				pqSecret.getBytes());
	}

	@Override
	public SecretKey kdfHybrid(SecretKey rootKey, @Nullable byte[] dhSecret,
			@Nullable byte[] pqSecret, boolean deriveChainKey) {
		if (dhSecret == null && pqSecret == null) {
			throw new IllegalArgumentException("At least one secret required");
		}

		ByteArrayOutputStream combined = new ByteArrayOutputStream();
		try {
			if (dhSecret != null) combined.write(dhSecret);
			if (pqSecret != null) combined.write(pqSecret);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}

		String label = deriveChainKey ? PCS_HYBRID_CHAIN_LABEL :
				PCS_HYBRID_ROOT_LABEL;
		return crypto.deriveKey(label, rootKey, combined.toByteArray());
	}

	private byte[] assembleEncapsulationKey(byte[] ekSeed, byte[] ekVector) {
		byte[] fullKey = new byte[ekSeed.length + ekVector.length];
		System.arraycopy(ekSeed, 0, fullKey, 0, ekSeed.length);
		System.arraycopy(ekVector, 0, fullKey, ekSeed.length, ekVector.length);
		return fullKey;
	}
}
