package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.pcs.MlKemEncapsulation;
import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.PcsConstants;
import org.zerionproject.core.api.crypto.pcs.PcsException;
import org.zerionproject.core.api.crypto.pcs.PqChunk;
import org.zerionproject.core.api.crypto.pcs.PqEpochState;
import org.zerionproject.core.api.crypto.pcs.PqRatchet;
import org.zerionproject.core.api.crypto.pcs.PqRatchetState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_HYBRID_CHAIN_LABEL;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_HYBRID_ROOT_LABEL;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PCS_PQ_ROOT_UPDATE_LABEL;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PQ_CIPHERTEXT_CHUNKS;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PQ_EK_VECTOR_CHUNKS;

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
				return updated.withPhaseTransition(
						PqEpochState.PQ_SENDING_EK_VEC);

			case PQ_SENDING_EK_VEC:
				if (updated.getChunksSent() >= PQ_EK_VECTOR_CHUNKS) {
					return updated.withPhaseTransition(
							PqEpochState.PQ_AWAITING_CT);
				}
				return updated;

			case PQ_SENDING_CT:
				if (updated.getChunksSent() >= PQ_CIPHERTEXT_CHUNKS) {
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
		byte chunkType = chunk.getType();
		PqEpochState st = state.getState();

		if (chunkType == PcsConstants.PQ_CHUNK_TYPE_EK_SEED) {
			return handleEkSeedChunk(state, chunk);
		}

		if (chunkType == PcsConstants.PQ_CHUNK_TYPE_EK_VEC) {
			if (st != PqEpochState.PQ_RECEIVING_EK_VEC) {
				return state;
			}
			return handleEkVectorChunk(state, chunk);
		}

		if (chunkType == PcsConstants.PQ_CHUNK_TYPE_CT) {
			if (st != PqEpochState.PQ_AWAITING_CT) {
				return state;
			}
			return handleCiphertextChunk(state, chunk);
		}

		return state;
	}

	private PqRatchetState handleEkSeedChunk(PqRatchetState state,
			PqChunk chunk) {
		if (chunk.getIndex() != 0) return state;
		byte[] data = chunk.getData();
		if (data.length != PcsConstants.MLKEM_EK_SEED_TOTAL_SIZE) return state;
		byte[] peerEkSeed = Arrays.copyOfRange(data, 0,
				PcsConstants.MLKEM_EK_SEED_SIZE);
		byte[] peerEkHash = Arrays.copyOfRange(data,
				PcsConstants.MLKEM_EK_SEED_SIZE,
				PcsConstants.MLKEM_EK_SEED_TOTAL_SIZE);

		PqEpochState st = state.getState();
		if (st == PqEpochState.PQ_READY
				|| st == PqEpochState.PQ_COMPLETE
				|| st == PqEpochState.PQ_INACTIVE) {
			return state.startResponderEpoch(peerEkSeed, peerEkHash);
		}

		if (st == PqEpochState.PQ_SENDING_EK_SEED
				|| st == PqEpochState.PQ_SENDING_EK_VEC) {
			MlKemKeyPair ourKp = state.getOurKeyPair();
			if (ourKp == null) {
				return state.startResponderEpoch(peerEkSeed, peerEkHash);
			}
			byte[] ourSeed = ourKp.getEkSeed();
			int cmp = compareBytes(ourSeed, peerEkSeed);
			if (cmp < 0) {
				return state;
			}
			return state.startResponderEpoch(peerEkSeed, peerEkHash);
		}

		return state;
	}

	private PqRatchetState handleEkVectorChunk(PqRatchetState state,
			PqChunk chunk) {
		int expectedIndex = state.getChunksReceived();
		if (chunk.getIndex() != expectedIndex) {
			return state.reset();
		}
		byte[] pending = state.getPendingChunks();
		if (pending == null) pending = new byte[0];
		byte[] newPending = chunkingManager.appendChunk(pending,
				chunk.getData());
		PqRatchetState updated = state.withChunkReceived(newPending);

		if (updated.getChunksReceived() < PQ_EK_VECTOR_CHUNKS) {
			return updated;
		}
		byte[] ekVector = chunkingManager.assembleEkVector(newPending,
				PQ_EK_VECTOR_CHUNKS);
		if (ekVector == null) return state.reset();
		byte[] ekSeed = state.getTheirEkSeed();
		byte[] ekHash = state.getTheirEkHash();
		if (ekSeed == null || ekHash == null) return state.reset();
		if (!mlKemProvider.verifyEkHash(ekSeed, ekVector, ekHash)) {
			return state.reset();
		}
		byte[] fullEk = assembleEncapsulationKey(ekSeed, ekVector);
		MlKemEncapsulation enc = mlKemProvider.encapsulate(fullEk);
		byte[] ct = enc.getCiphertext();
		byte[] sharedSecret = enc.getSharedSecret().clone();
		Arrays.fill(enc.getSharedSecret(), (byte) 0);
		return updated
				.withEkVector(ekVector)
				.withCiphertext(ct)
				.withSharedSecret(sharedSecret)
				.withPhaseTransition(PqEpochState.PQ_SENDING_CT);
	}

	private PqRatchetState handleCiphertextChunk(PqRatchetState state,
			PqChunk chunk) {
		int expectedIndex = state.getChunksReceived();
		if (chunk.getIndex() != expectedIndex) {
			return state.reset();
		}
		byte[] pending = state.getPendingChunks();
		if (pending == null) pending = new byte[0];
		byte[] newPending = chunkingManager.appendChunk(pending,
				chunk.getData());
		PqRatchetState updated = state.withChunkReceived(newPending);

		if (updated.getChunksReceived() < PQ_CIPHERTEXT_CHUNKS) {
			return updated;
		}
		byte[] ct = chunkingManager.assembleCiphertext(newPending,
				PQ_CIPHERTEXT_CHUNKS);
		if (ct == null) return state.reset();
		return updated
				.withCiphertext(ct)
				.withState(PqEpochState.PQ_COMPLETE);
	}

	private static int compareBytes(byte[] a, byte[] b) {
		int n = Math.min(a.length, b.length);
		for (int i = 0; i < n; i++) {
			int ai = a[i] & 0xFF;
			int bi = b[i] & 0xFF;
			if (ai != bi) return ai - bi;
		}
		return a.length - b.length;
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
			byte[] stored = state.getPqSharedSecret();
			if (stored == null) {
				throw new PcsException("Missing shared secret");
			}
			sharedSecret = stored.clone();
			Arrays.fill(stored, (byte) 0);
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
