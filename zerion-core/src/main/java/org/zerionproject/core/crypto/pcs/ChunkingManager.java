package org.zerionproject.core.crypto.pcs;

import org.zerionproject.core.api.crypto.pcs.MlKemKeyPair;
import org.zerionproject.core.api.crypto.pcs.MlKemProvider;
import org.zerionproject.core.api.crypto.pcs.PqChunk;
import org.zerionproject.core.api.crypto.pcs.PqRatchetState;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import javax.annotation.Nullable;
import javax.inject.Inject;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_CIPHERTEXT_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_EK_SEED_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_EK_SEED_TOTAL_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.MLKEM_EK_VECTOR_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PQ_CHUNK_SIZE;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PQ_CHUNK_TYPE_CT;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PQ_CHUNK_TYPE_EK_SEED;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PQ_CHUNK_TYPE_EK_VEC;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PQ_CIPHERTEXT_CHUNKS;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PQ_EK_VECTOR_CHUNKS;

@NotNullByDefault
class ChunkingManager {

	private final MlKemProvider mlKemProvider;

	@Inject
	ChunkingManager(MlKemProvider mlKemProvider) {
		this.mlKemProvider = mlKemProvider;
	}

	@Nullable
	PqChunk getNextChunkToSend(PqRatchetState state) {
		switch (state.getState()) {
			case PQ_SENDING_EK_SEED:
				return getEkSeedChunk(state);
			case PQ_SENDING_EK_VEC:
				return getEkVectorChunk(state);
			case PQ_SENDING_CT:
				return getCiphertextChunk(state);
			default:
				return null;
		}
	}

	@Nullable
	private PqChunk getEkSeedChunk(PqRatchetState state) {
		MlKemKeyPair keyPair = state.getOurKeyPair();
		if (keyPair == null) return null;

		byte[] seed = keyPair.getEkSeed();
		byte[] ekHash = mlKemProvider.hashEkSeedAndVector(
				seed, keyPair.getEkVector());
		byte[] seedAndHash = new byte[MLKEM_EK_SEED_TOTAL_SIZE];
		System.arraycopy(seed, 0, seedAndHash, 0, MLKEM_EK_SEED_SIZE);
		System.arraycopy(ekHash, 0, seedAndHash, MLKEM_EK_SEED_SIZE,
				ekHash.length);

		return new PqChunk(PQ_CHUNK_TYPE_EK_SEED, 0, seedAndHash);
	}

	@Nullable
	private PqChunk getEkVectorChunk(PqRatchetState state) {
		MlKemKeyPair keyPair = state.getOurKeyPair();
		if (keyPair == null) return null;

		int chunkIndex = state.getChunksSent();
		if (chunkIndex >= PQ_EK_VECTOR_CHUNKS) return null;

		byte[] ekVector = keyPair.getEkVector();
		int offset = chunkIndex * PQ_CHUNK_SIZE;
		int remaining = ekVector.length - offset;
		int chunkLength = Math.min(PQ_CHUNK_SIZE, remaining);

		byte[] chunkData = Arrays.copyOfRange(ekVector, offset,
				offset + chunkLength);
		return new PqChunk(PQ_CHUNK_TYPE_EK_VEC, chunkIndex, chunkData);
	}

	@Nullable
	private PqChunk getCiphertextChunk(PqRatchetState state) {
		byte[] ciphertext = state.getCiphertext();
		if (ciphertext == null) return null;

		int chunkIndex = state.getChunksSent();
		if (chunkIndex >= PQ_CIPHERTEXT_CHUNKS) return null;

		int offset = chunkIndex * PQ_CHUNK_SIZE;
		int remaining = ciphertext.length - offset;
		int chunkLength = Math.min(PQ_CHUNK_SIZE, remaining);

		byte[] chunkData = Arrays.copyOfRange(ciphertext, offset,
				offset + chunkLength);
		return new PqChunk(PQ_CHUNK_TYPE_CT, chunkIndex, chunkData);
	}

	@Nullable
	byte[] assembleEkVector(byte[] pendingChunks, int expectedChunks) {
		if (pendingChunks.length < MLKEM_EK_VECTOR_SIZE) return null;
		return Arrays.copyOf(pendingChunks, MLKEM_EK_VECTOR_SIZE);
	}

	@Nullable
	byte[] assembleCiphertext(byte[] pendingChunks, int expectedChunks) {
		if (pendingChunks.length < MLKEM_CIPHERTEXT_SIZE) return null;
		return Arrays.copyOf(pendingChunks, MLKEM_CIPHERTEXT_SIZE);
	}

	byte[] appendChunk(byte[] existing, byte[] newChunk) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		try {
			baos.write(existing);
			baos.write(newChunk);
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return baos.toByteArray();
	}

	boolean isEkVectorComplete(PqRatchetState state) {
		return state.getChunksReceived() >= PQ_EK_VECTOR_CHUNKS;
	}

	boolean isCiphertextComplete(PqRatchetState state) {
		return state.getChunksReceived() >= PQ_CIPHERTEXT_CHUNKS;
	}

	boolean isEkVectorSendComplete(PqRatchetState state) {
		return state.getChunksSent() >= PQ_EK_VECTOR_CHUNKS;
	}

	boolean isCiphertextSendComplete(PqRatchetState state) {
		return state.getChunksSent() >= PQ_CIPHERTEXT_CHUNKS;
	}
}
