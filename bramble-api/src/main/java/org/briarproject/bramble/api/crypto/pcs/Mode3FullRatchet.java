package org.briarproject.bramble.api.crypto.pcs;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface Mode3FullRatchet {

	Mode3FullState createInitialState(SecretKey rootKey);

	PqSendResult pqEncapsulateSend(Mode3FullState state);

	PqRecvResult pqDecapsulateRecv(Mode3FullState state, KpId kpId,
			byte[] ciphertext, byte[] theirNewPqPk) throws PcsException;

	SecretKey deriveHybridMessageKey(SecretKey classicalMessageKey,
			SecretKey ckPq);

	@NotNullByDefault
	final class PqSendResult {

		private final byte[] pkAdvertise;
		private final byte[] ciphertext;
		@javax.annotation.Nullable
		private final KpId kpIdUsed;
		private final SecretKey newCkPq;
		private final Mode3FullState newState;

		public PqSendResult(byte[] pkAdvertise, byte[] ciphertext,
				@javax.annotation.Nullable KpId kpIdUsed,
				SecretKey newCkPq, Mode3FullState newState) {
			this.pkAdvertise = pkAdvertise;
			this.ciphertext = ciphertext;
			this.kpIdUsed = kpIdUsed;
			this.newCkPq = newCkPq;
			this.newState = newState;
		}

		public byte[] getPkAdvertise() {
			return pkAdvertise;
		}

		public byte[] getCiphertext() {
			return ciphertext;
		}

		@javax.annotation.Nullable
		public KpId getKpIdUsed() {
			return kpIdUsed;
		}

		public SecretKey getNewCkPq() {
			return newCkPq;
		}

		public Mode3FullState getNewState() {
			return newState;
		}
	}

	@NotNullByDefault
	final class PqRecvResult {

		private final SecretKey newCkPq;
		private final Mode3FullState newState;

		public PqRecvResult(SecretKey newCkPq, Mode3FullState newState) {
			this.newCkPq = newCkPq;
			this.newState = newState;
		}

		public SecretKey getNewCkPq() {
			return newCkPq;
		}

		public Mode3FullState getNewState() {
			return newState;
		}
	}
}
