package org.zerionproject.core.api.crypto.pcs;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface PcsRatchet {

	class KdfCkResult {
		private final SecretKey newChainKey;
		private final SecretKey messageKey;

		public KdfCkResult(SecretKey newChainKey, SecretKey messageKey) {
			this.newChainKey = newChainKey;
			this.messageKey = messageKey;
		}

		public SecretKey getNewChainKey() {
			return newChainKey;
		}

		public SecretKey getMessageKey() {
			return messageKey;
		}
	}

	KdfCkResult kdfCk(SecretKey chainKey);

	SecretKey deriveStreamInitialChainKey(SecretKey rootKey, long streamNumber,
			byte[] salt);
}
