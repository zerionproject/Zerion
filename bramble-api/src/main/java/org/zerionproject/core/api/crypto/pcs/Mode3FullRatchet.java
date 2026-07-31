package org.zerionproject.core.api.crypto.pcs;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface Mode3FullRatchet {

	Mode3FullState createInitialState();

	PqSendResult pqEncapsulateSend(Mode3FullState state);

	PqRecvResult pqDecapsulateRecv(Mode3FullState state, KpId kpId,
			byte[] ciphertext, byte[] theirNewPqPk) throws PcsException;

	SecretKey deriveHybridMessageKey(SecretKey classicalMessageKey,
			byte[] sharedSecret);

	/**
	 * Absorbs a post-quantum shared secret into a stream chain key. Both
	 * endpoints derive the same secret for the same frame, so applying this at
	 * the same point in the chain keeps the two sides in step. Once absorbed,
	 * the chain can no longer be recomputed from the contact root key alone.
	 */
	SecretKey mixPqSecretIntoChainKey(SecretKey chainKey, byte[] sharedSecret);

	@NotNullByDefault
	final class PqSendResult {

		private final byte[] pkAdvertise;
		private final byte[] ciphertext;
		@Nullable
		private final KpId kpIdUsed;
		@Nullable
		private final byte[] sharedSecret;
		private final Mode3FullState newState;

		public PqSendResult(byte[] pkAdvertise, byte[] ciphertext,
				@Nullable KpId kpIdUsed, @Nullable byte[] sharedSecret,
				Mode3FullState newState) {
			this.pkAdvertise = pkAdvertise;
			this.ciphertext = ciphertext;
			this.kpIdUsed = kpIdUsed;
			this.sharedSecret = sharedSecret;
			this.newState = newState;
		}

		public byte[] getPkAdvertise() {
			return pkAdvertise;
		}

		public byte[] getCiphertext() {
			return ciphertext;
		}

		@Nullable
		public KpId getKpIdUsed() {
			return kpIdUsed;
		}

		@Nullable
		public byte[] getSharedSecret() {
			return sharedSecret;
		}

		public Mode3FullState getNewState() {
			return newState;
		}
	}

	@NotNullByDefault
	final class PqRecvResult {

		@Nullable
		private final byte[] sharedSecret;
		private final Mode3FullState newState;

		public PqRecvResult(@Nullable byte[] sharedSecret,
				Mode3FullState newState) {
			this.sharedSecret = sharedSecret;
			this.newState = newState;
		}

		@Nullable
		public byte[] getSharedSecret() {
			return sharedSecret;
		}

		public Mode3FullState getNewState() {
			return newState;
		}
	}
}
