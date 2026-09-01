package org.zerionproject.core.api.crypto.pcs;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public interface Mode3FullRatchet {

	Mode3FullState createInitialState();

	/**
	 * Encapsulate a post-quantum secret for the next outgoing frame. Rotation of
	 * our own ML-KEM key pair is driven by {@code ownSendsSinceRotation}, the
	 * number of our own sends since our key pair was last rotated, so a key pair
	 * is never advertised for more than {@link
	 * org.zerionproject.core.api.crypto.pcs.PcsConstants#MODE3_FULL_SEND_ROTATION_INTERVAL}
	 * of our sends regardless of how the peer interleaves its traffic. The caller
	 * (the send side) owns this counter and resets it whenever
	 * {@link PqSendResult#isRotated()} is true.
	 */
	PqSendResult pqEncapsulateSend(Mode3FullState state,
			long ownSendsSinceRotation);

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
		private final boolean rotated;

		public PqSendResult(byte[] pkAdvertise, byte[] ciphertext,
				@Nullable KpId kpIdUsed, @Nullable byte[] sharedSecret,
				Mode3FullState newState, boolean rotated) {
			this.pkAdvertise = pkAdvertise;
			this.ciphertext = ciphertext;
			this.kpIdUsed = kpIdUsed;
			this.sharedSecret = sharedSecret;
			this.newState = newState;
			this.rotated = rotated;
		}

		/** Whether our own ML-KEM key pair was rotated on this send; the send
		 *  side resets its own-send counter when this is true. */
		public boolean isRotated() {
			return rotated;
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
