package org.zerionproject.core.api.crypto.pcs;

import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

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

	SecretKey derivePcsRootKey(SecretKey contactRootKey);

	KdfCkResult kdfCk(SecretKey chainKey);

	SecretKey deriveStreamInitialChainKey(SecretKey rootKey, long streamNumber);

	/**
	 * Seeds a stream chain from the root key, the stream number and a
	 * per-stream salt. The salt is the random stream-header nonce, which both
	 * endpoints see, so the two sides agree while the chain no longer depends
	 * on the stream number alone. Without it, a database snapshot restored
	 * onto a second device would re-issue stream numbers that had already been
	 * used under the same long-lived root key, repeating a (key, nonce) pair.
	 */
	SecretKey deriveStreamInitialChainKey(SecretKey rootKey, long streamNumber,
			byte[] salt);

	AdvanceResult advanceSendChain(PcsSessionState state);

	AdvanceResult advanceReceiveChain(PcsSessionState state, int messageNumber,
			SkippedKeyStore skippedKeyStore) throws PcsException;

	class AdvanceResult {
		private final SecretKey messageKey;
		private final PcsSessionState newState;

		public AdvanceResult(SecretKey messageKey, PcsSessionState newState) {
			this.messageKey = messageKey;
			this.newState = newState;
		}

		public SecretKey getMessageKey() {
			return messageKey;
		}

		public PcsSessionState getNewState() {
			return newState;
		}
	}

	class KdfRkResult {
		private final SecretKey newRootKey;
		private final SecretKey chainKey;

		public KdfRkResult(SecretKey newRootKey, SecretKey chainKey) {
			this.newRootKey = newRootKey;
			this.chainKey = chainKey;
		}

		public SecretKey getNewRootKey() {
			return newRootKey;
		}

		public SecretKey getChainKey() {
			return chainKey;
		}
	}

	class DhRatchetResult {
		private final PcsSessionState newState;
		private final PublicKey dhPublicKey;

		public DhRatchetResult(PcsSessionState newState, PublicKey dhPublicKey) {
			this.newState = newState;
			this.dhPublicKey = dhPublicKey;
		}

		public PcsSessionState getNewState() {
			return newState;
		}

		public PublicKey getDhPublicKey() {
			return dhPublicKey;
		}
	}

	KdfRkResult kdfRk(SecretKey rootKey, byte[] dhOutput);

	KeyPair generateDhKeyPair();

	DhRatchetResult performSendDhRatchet(PcsSessionState state)
			throws GeneralSecurityException, PcsException;

	DhRatchetResult performReceiveDhRatchet(PcsSessionState state,
			PublicKey theirNewPublicKey)
			throws GeneralSecurityException, PcsException;

	PcsSessionState initializeMode2AsInitiator(SecretKey rootKey);

	PcsSessionState initializeMode2AsResponder(SecretKey rootKey,
			PublicKey theirPublicKey) throws GeneralSecurityException;
}
