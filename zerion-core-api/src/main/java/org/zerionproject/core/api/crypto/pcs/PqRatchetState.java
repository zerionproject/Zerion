package org.zerionproject.core.api.crypto.pcs;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PQ_EPOCH_MESSAGE_THRESHOLD;
import static org.zerionproject.core.api.crypto.pcs.PcsConstants.PQ_EPOCH_TIME_THRESHOLD_MS;

@Immutable
@NotNullByDefault
public class PqRatchetState {

	private final long currentEpoch;
	private final long epochStartTime;
	private final int messagesSinceEpoch;
	private final PqEpochState state;
	private final boolean isInitiator;
	private final int chunksSent;
	private final int chunksReceived;

	@Nullable
	private final MlKemKeyPair ourKeyPair;
	@Nullable
	private final byte[] theirEkSeed;
	@Nullable
	private final byte[] theirEkHash;
	@Nullable
	private final byte[] theirEkVector;
	@Nullable
	private final byte[] ciphertext;
	@Nullable
	private final byte[] pendingChunks;
	@Nullable
	private final byte[] pqSharedSecret;

	private PqRatchetState(long currentEpoch, long epochStartTime,
			int messagesSinceEpoch, PqEpochState state, boolean isInitiator,
			int chunksSent, int chunksReceived,
			@Nullable MlKemKeyPair ourKeyPair,
			@Nullable byte[] theirEkSeed,
			@Nullable byte[] theirEkHash,
			@Nullable byte[] theirEkVector,
			@Nullable byte[] ciphertext,
			@Nullable byte[] pendingChunks,
			@Nullable byte[] pqSharedSecret) {
		this.currentEpoch = currentEpoch;
		this.epochStartTime = epochStartTime;
		this.messagesSinceEpoch = messagesSinceEpoch;
		this.state = state;
		this.isInitiator = isInitiator;
		this.chunksSent = chunksSent;
		this.chunksReceived = chunksReceived;
		this.ourKeyPair = ourKeyPair;
		this.theirEkSeed = theirEkSeed;
		this.theirEkHash = theirEkHash;
		this.theirEkVector = theirEkVector;
		this.ciphertext = ciphertext;
		this.pendingChunks = pendingChunks;
		this.pqSharedSecret = pqSharedSecret;
	}

	public static PqRatchetState createInactive() {
		return new PqRatchetState(0, 0, 0, PqEpochState.PQ_INACTIVE,
				false, 0, 0, null, null, null, null, null, null, null);
	}

	public static PqRatchetState createReady(long epochStartTime) {
		return new PqRatchetState(0, epochStartTime, 0, PqEpochState.PQ_READY,
				false, 0, 0, null, null, null, null, null, null, null);
	}

	public static PqRatchetState fromDatabase(long currentEpoch, long epochStartTime,
			int messagesSinceEpoch, PqEpochState state, boolean isInitiator,
			int chunksSent, int chunksReceived,
			@Nullable MlKemKeyPair ourKeyPair,
			@Nullable byte[] theirEkSeed,
			@Nullable byte[] theirEkHash,
			@Nullable byte[] theirEkVector,
			@Nullable byte[] ciphertext,
			@Nullable byte[] pendingChunks) {
		return new PqRatchetState(currentEpoch, epochStartTime,
				messagesSinceEpoch, state, isInitiator,
				chunksSent, chunksReceived, ourKeyPair, theirEkSeed,
				theirEkHash, theirEkVector, ciphertext, pendingChunks, null);
	}

	public long getCurrentEpoch() {
		return currentEpoch;
	}

	public long getEpochStartTime() {
		return epochStartTime;
	}

	public int getMessagesSinceEpoch() {
		return messagesSinceEpoch;
	}

	public PqEpochState getState() {
		return state;
	}

	public boolean isInitiator() {
		return isInitiator;
	}

	public int getChunksSent() {
		return chunksSent;
	}

	public int getChunksReceived() {
		return chunksReceived;
	}

	@Nullable
	public MlKemKeyPair getOurKeyPair() {
		return ourKeyPair;
	}

	@Nullable
	public byte[] getTheirEkSeed() {
		return theirEkSeed;
	}

	@Nullable
	public byte[] getTheirEkHash() {
		return theirEkHash;
	}

	@Nullable
	public byte[] getTheirEkVector() {
		return theirEkVector;
	}

	@Nullable
	public byte[] getCiphertext() {
		return ciphertext;
	}

	@Nullable
	public byte[] getPendingChunks() {
		return pendingChunks;
	}

	@Nullable
	public byte[] getPqSharedSecret() {
		return pqSharedSecret;
	}

	public boolean isActive() {
		return state.isActive();
	}

	public boolean shouldStartNewEpoch(long currentTime) {
		long age = currentTime - epochStartTime;
		if (state == PqEpochState.PQ_READY) {
			if (messagesSinceEpoch >= PQ_EPOCH_MESSAGE_THRESHOLD) return true;
			return age >= PQ_EPOCH_TIME_THRESHOLD_MS;
		}
		if (state == PqEpochState.PQ_INACTIVE
				|| state == PqEpochState.PQ_COMPLETE) {
			return false;
		}
		if (messagesSinceEpoch >= 2 * PQ_EPOCH_MESSAGE_THRESHOLD) return true;
		return age >= PQ_EPOCH_TIME_THRESHOLD_MS;
	}

	public PqRatchetState incrementMessageCount() {
		return new PqRatchetState(currentEpoch, epochStartTime,
				messagesSinceEpoch + 1, state, isInitiator,
				chunksSent, chunksReceived, ourKeyPair, theirEkSeed,
				theirEkHash, theirEkVector, ciphertext, pendingChunks,
				pqSharedSecret);
	}

	public PqRatchetState withState(PqEpochState newState) {
		return new PqRatchetState(currentEpoch, epochStartTime,
				messagesSinceEpoch, newState, isInitiator,
				chunksSent, chunksReceived, ourKeyPair, theirEkSeed,
				theirEkHash, theirEkVector, ciphertext, pendingChunks,
				pqSharedSecret);
	}

	public PqRatchetState startInitiatorEpoch(MlKemKeyPair keyPair) {
		return new PqRatchetState(currentEpoch, epochStartTime,
				messagesSinceEpoch, PqEpochState.PQ_SENDING_EK_SEED, true,
				0, 0, keyPair, null, null, null, null, null, null);
	}

	public PqRatchetState startResponderEpoch(byte[] ekSeed, byte[] ekHash) {
		return new PqRatchetState(currentEpoch, epochStartTime,
				messagesSinceEpoch, PqEpochState.PQ_RECEIVING_EK_VEC, false,
				0, 0, null, ekSeed, ekHash, null, null, new byte[0], null);
	}

	public PqRatchetState withPhaseTransition(PqEpochState newState) {
		return new PqRatchetState(currentEpoch, epochStartTime,
				messagesSinceEpoch, newState, isInitiator,
				0, 0, ourKeyPair, theirEkSeed, theirEkHash, theirEkVector,
				ciphertext, null, pqSharedSecret);
	}

	public PqRatchetState withChunkSent() {
		return new PqRatchetState(currentEpoch, epochStartTime,
				messagesSinceEpoch, state, isInitiator,
				chunksSent + 1, chunksReceived, ourKeyPair, theirEkSeed,
				theirEkHash, theirEkVector, ciphertext, pendingChunks,
				pqSharedSecret);
	}

	public PqRatchetState withChunkReceived(byte[] newPendingChunks) {
		return new PqRatchetState(currentEpoch, epochStartTime,
				messagesSinceEpoch, state, isInitiator,
				chunksSent, chunksReceived + 1, ourKeyPair, theirEkSeed,
				theirEkHash, theirEkVector, ciphertext, newPendingChunks,
				pqSharedSecret);
	}

	public PqRatchetState withEkVector(byte[] ekVector) {
		return new PqRatchetState(currentEpoch, epochStartTime,
				messagesSinceEpoch, state, isInitiator,
				chunksSent, chunksReceived, ourKeyPair, theirEkSeed,
				theirEkHash, ekVector, ciphertext, null, pqSharedSecret);
	}

	public PqRatchetState withCiphertext(byte[] ct) {
		return new PqRatchetState(currentEpoch, epochStartTime,
				messagesSinceEpoch, state, isInitiator,
				chunksSent, chunksReceived, ourKeyPair, theirEkSeed,
				theirEkHash, theirEkVector, ct, pendingChunks,
				pqSharedSecret);
	}

	public PqRatchetState withSharedSecret(byte[] sharedSecret) {
		return new PqRatchetState(currentEpoch, epochStartTime,
				messagesSinceEpoch, state, isInitiator,
				chunksSent, chunksReceived, ourKeyPair, theirEkSeed,
				theirEkHash, theirEkVector, ciphertext, pendingChunks,
				sharedSecret);
	}

	public PqRatchetState completeEpoch(long newEpochStartTime) {
		return new PqRatchetState(currentEpoch + 1, newEpochStartTime,
				0, PqEpochState.PQ_READY, false,
				0, 0, null, null, null, null, null, null, null);
	}

	public PqRatchetState reset() {
		return new PqRatchetState(currentEpoch, epochStartTime,
				messagesSinceEpoch, PqEpochState.PQ_READY, false,
				0, 0, null, null, null, null, null, null, null);
	}
}
