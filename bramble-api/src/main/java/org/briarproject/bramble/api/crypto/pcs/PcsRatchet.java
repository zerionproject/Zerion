package org.briarproject.bramble.api.crypto.pcs;

import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

/**
 * Interface for the PCS ratchet (Mode 1 symmetric + Mode 2 DH ratchet).
 * <p>
 * The symmetric ratchet provides per-message forward secrecy by deriving
 * a unique message key for each message. Once a message key is used, it
 * is deleted and cannot be recovered, preventing decryption of past messages
 * if the current state is compromised.
 * <p>
 * Mode 2 adds a DH ratchet for post-compromise security, where each message
 * includes a new ephemeral DH public key, allowing recovery from compromise
 * within one round-trip.
 * <p>
 * Key derivation uses BLAKE2b with domain-separated labels:
 * <pre>
 * KDF_CK(chain_key) → (new_chain_key, message_key)
 *   new_chain_key = BLAKE2b(label: PCS_CHAIN_KEY, key: chain_key, input: 0x01)
 *   message_key   = BLAKE2b(label: PCS_MESSAGE_KEY, key: chain_key, input: 0x02)
 *
 * KDF_RK(root_key, dh_output) → (new_root_key, chain_key)
 *   temp = BLAKE2b-512(label: PCS_ROOT_KDF, key: root_key, input: dh_output)
 *   new_root_key = temp[0:32]
 *   chain_key = temp[32:64]
 * </pre>
 */
@NotNullByDefault
public interface PcsRatchet {

	/**
	 * Result of a KDF_CK operation containing both the new chain key
	 * and the derived message key.
	 */
	class KdfCkResult {
		private final SecretKey newChainKey;
		private final SecretKey messageKey;

		public KdfCkResult(SecretKey newChainKey, SecretKey messageKey) {
			this.newChainKey = newChainKey;
			this.messageKey = messageKey;
		}

		/**
		 * Returns the new chain key to be stored for the next message.
		 */
		public SecretKey getNewChainKey() {
			return newChainKey;
		}

		/**
		 * Returns the message key to use for encrypting/decrypting this message.
		 * This key should be deleted immediately after use.
		 */
		public SecretKey getMessageKey() {
			return messageKey;
		}
	}

	/**
	 * Derives the initial PCS root key from the contact root key.
	 * <p>
	 * This is called once during handshake completion to initialize
	 * the PCS state for a contact.
	 *
	 * @param contactRootKey The root key derived from the handshake
	 * @return The PCS root key to use as the initial chain key
	 */
	SecretKey derivePcsRootKey(SecretKey contactRootKey);

	/**
	 * Performs the KDF_CK operation to derive the next chain key
	 * and a message key from the current chain key.
	 * <p>
	 * This is the core ratchet operation:
	 * <pre>
	 * CK[n] → CK[n+1], MK[n]
	 * </pre>
	 *
	 * @param chainKey The current chain key
	 * @return The new chain key and message key
	 */
	KdfCkResult kdfCk(SecretKey chainKey);

	/**
	 * Advances the send chain and returns the message key.
	 * <p>
	 * This method:
	 * 1. Derives new_chain_key and message_key from current chain key
	 * 2. Updates the send state with new_chain_key and incremented counter
	 * 3. Returns the message_key for encryption
	 *
	 * @param state The current send state
	 * @return Result containing the message key and updated state
	 */
	AdvanceResult advanceSendChain(PcsSessionState state);

	/**
	 * Advances the receive chain to the specified message number and
	 * returns the message key.
	 * <p>
	 * If the message number is ahead of the current state, this method
	 * derives and stores skipped keys (bounded by MAX_SKIP). If the
	 * message number matches a previously skipped key, that key is
	 * retrieved and deleted.
	 *
	 * @param state The current receive state
	 * @param messageNumber The message number from the received message header
	 * @param skippedKeyStore Store for managing skipped keys
	 * @return Result containing the message key and updated state
	 * @throws PcsException If the message number is invalid or too far ahead
	 */
	AdvanceResult advanceReceiveChain(PcsSessionState state, int messageNumber,
			SkippedKeyStore skippedKeyStore) throws PcsException;

	/**
	 * Result of advancing the chain, containing both the message key
	 * and the updated session state.
	 */
	class AdvanceResult {
		private final SecretKey messageKey;
		private final PcsSessionState newState;

		public AdvanceResult(SecretKey messageKey, PcsSessionState newState) {
			this.messageKey = messageKey;
			this.newState = newState;
		}

		/**
		 * Returns the message key for encryption/decryption.
		 */
		public SecretKey getMessageKey() {
			return messageKey;
		}

		/**
		 * Returns the updated session state to persist.
		 */
		public PcsSessionState getNewState() {
			return newState;
		}
	}

	// ==================== Mode 2: DH Ratchet ====================

	/**
	 * Result of a KDF_RK operation containing the new root key and chain key.
	 */
	class KdfRkResult {
		private final SecretKey newRootKey;
		private final SecretKey chainKey;

		public KdfRkResult(SecretKey newRootKey, SecretKey chainKey) {
			this.newRootKey = newRootKey;
			this.chainKey = chainKey;
		}

		/**
		 * Returns the new root key to store for the next DH ratchet step.
		 */
		public SecretKey getNewRootKey() {
			return newRootKey;
		}

		/**
		 * Returns the chain key to use for the new sending or receiving chain.
		 */
		public SecretKey getChainKey() {
			return chainKey;
		}
	}

	/**
	 * Result of a DH ratchet step, containing the new state and optionally
	 * the DH public key to include in the message.
	 */
	class DhRatchetResult {
		private final PcsSessionState newState;
		private final PublicKey dhPublicKey;

		public DhRatchetResult(PcsSessionState newState, PublicKey dhPublicKey) {
			this.newState = newState;
			this.dhPublicKey = dhPublicKey;
		}

		/**
		 * Returns the updated session state after the DH ratchet step.
		 */
		public PcsSessionState getNewState() {
			return newState;
		}

		/**
		 * Returns our DH public key to include in the message header.
		 */
		public PublicKey getDhPublicKey() {
			return dhPublicKey;
		}
	}

	/**
	 * Performs the KDF_RK operation to derive new root and chain keys
	 * from the DH output.
	 * <p>
	 * KDF_RK(rk, dh_out) → (new_rk, chain_key)
	 * <pre>
	 * temp = BLAKE2b-512(label: PCS_ROOT_KDF, key: rk, input: dh_out)
	 * new_rk = temp[0:32]
	 * chain_key = temp[32:64]
	 * </pre>
	 *
	 * @param rootKey The current root key
	 * @param dhOutput The 32-byte DH shared secret
	 * @return The new root key and chain key
	 */
	KdfRkResult kdfRk(SecretKey rootKey, byte[] dhOutput);

	/**
	 * Generates a new DH ratchet key pair.
	 *
	 * @return A fresh X25519 key pair for the DH ratchet
	 */
	KeyPair generateDhKeyPair();

	/**
	 * Performs a DH ratchet step when sending (Mode 2).
	 * <p>
	 * This is called before sending a message to:
	 * 1. Generate a new DH key pair if needed
	 * 2. Compute DH(DHs, DHr) if we have their public key
	 * 3. Derive new root and chain keys via KDF_RK
	 * 4. Return the updated state and our public key to send
	 *
	 * @param state The current session state (must be Mode 2)
	 * @return Result containing the new state and DH public key
	 * @throws GeneralSecurityException If DH computation fails
	 * @throws PcsException If the state is not Mode 2 or missing required keys
	 */
	DhRatchetResult performSendDhRatchet(PcsSessionState state)
			throws GeneralSecurityException, PcsException;

	/**
	 * Performs a DH ratchet step when receiving (Mode 2).
	 * <p>
	 * This is called when receiving a message with a new DH public key to:
	 * 1. Store their new DH public key
	 * 2. Compute DH(DHs, DHr_new)
	 * 3. Derive new root and receiving chain keys via KDF_RK
	 * 4. Generate a new DH key pair for our next send
	 * 5. Compute DH(DHs_new, DHr_new) for the sending chain
	 * 6. Derive new root and sending chain keys via KDF_RK
	 *
	 * @param state The current session state (must be Mode 2)
	 * @param theirNewPublicKey The peer's new DH public key from the message
	 * @return Result containing the new state with updated chains
	 * @throws GeneralSecurityException If DH computation fails
	 * @throws PcsException If the state is not Mode 2
	 */
	DhRatchetResult performReceiveDhRatchet(PcsSessionState state,
			PublicKey theirNewPublicKey)
			throws GeneralSecurityException, PcsException;

	/**
	 * Initializes a Mode 2 session state from a Mode 1 state.
	 * <p>
	 * This upgrades an existing symmetric ratchet session to use
	 * the full Double Ratchet with DH key exchange.
	 *
	 * @param mode1State The existing Mode 1 state
	 * @return A new Mode 2 state with DH ratchet initialized
	 */
	PcsSessionState initializeMode2(PcsSessionState mode1State);

	/**
	 * Initializes a Mode 2 session as the initiator (Alice).
	 * <p>
	 * Alice generates her initial DH key pair and waits for Bob's
	 * public key in his first message.
	 *
	 * @param rootKey The root key from the handshake
	 * @return Initial Mode 2 state for the initiator
	 */
	PcsSessionState initializeMode2AsInitiator(SecretKey rootKey);

	/**
	 * Initializes a Mode 2 session as the responder (Bob).
	 * <p>
	 * Bob receives Alice's DH public key and performs the first
	 * DH ratchet step immediately.
	 *
	 * @param rootKey The root key from the handshake
	 * @param theirPublicKey Alice's DH public key
	 * @return Initial Mode 2 state for the responder
	 * @throws GeneralSecurityException If DH computation fails
	 */
	PcsSessionState initializeMode2AsResponder(SecretKey rootKey,
			PublicKey theirPublicKey) throws GeneralSecurityException;
}
