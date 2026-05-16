package org.briarproject.bramble.api.crypto.pcs;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface Mode3FullRatchet {

	Mode3FullState createInitialState(SecretKey rootKey);

	SendChainResult advanceSendChain(Mode3FullState state);

	RecvChainResult advanceRecvChain(Mode3FullState state,
			byte[] ciphertext, byte[] theirNewPqPk) throws PcsException;

	@NotNullByDefault
	final class SendChainResult {

		private final SecretKey messageKey;
		private final byte[] pkAdvertise;
		private final byte[] ciphertext;
		private final Mode3FullState newState;

		public SendChainResult(SecretKey messageKey, byte[] pkAdvertise,
				byte[] ciphertext, Mode3FullState newState) {
			this.messageKey = messageKey;
			this.pkAdvertise = pkAdvertise;
			this.ciphertext = ciphertext;
			this.newState = newState;
		}

		public SecretKey getMessageKey() {
			return messageKey;
		}

		public byte[] getPkAdvertise() {
			return pkAdvertise;
		}

		public byte[] getCiphertext() {
			return ciphertext;
		}

		public Mode3FullState getNewState() {
			return newState;
		}
	}

	@NotNullByDefault
	final class RecvChainResult {

		private final SecretKey messageKey;
		private final Mode3FullState newState;

		public RecvChainResult(SecretKey messageKey, Mode3FullState newState) {
			this.messageKey = messageKey;
			this.newState = newState;
		}

		public SecretKey getMessageKey() {
			return messageKey;
		}

		public Mode3FullState getNewState() {
			return newState;
		}
	}
}
