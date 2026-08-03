package org.zerionproject.core.api.crypto.pcs;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface PqRatchet {

	PqRatchetState initialize(long currentTime);

	PqRatchetState startEpochAsInitiator(PqRatchetState state);

	PqRatchetState receiveEkSeed(PqRatchetState state, byte[] ekSeed,
			byte[] ekHash);

	@Nullable
	PqChunk getNextChunkToSend(PqRatchetState state);

	PqRatchetState processChunkSent(PqRatchetState state);

	PqRatchetState processChunkReceived(PqRatchetState state, PqChunk chunk);

	boolean isEpochComplete(PqRatchetState state);

	SecretKey deriveEpochSecret(PqRatchetState state) throws PcsException;

	PqRatchetState completeEpoch(PqRatchetState state, long currentTime);

	PqRatchetState incrementMessageCount(PqRatchetState state);

	boolean shouldStartNewEpoch(PqRatchetState state, long currentTime);

	SecretKey mixPqSecretIntoRootKey(SecretKey rootKey, SecretKey pqSecret);

	SecretKey kdfHybrid(SecretKey rootKey, @Nullable byte[] dhSecret,
			@Nullable byte[] pqSecret, boolean deriveChainKey);
}
