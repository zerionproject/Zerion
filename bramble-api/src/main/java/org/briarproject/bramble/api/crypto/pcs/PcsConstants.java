package org.briarproject.bramble.api.crypto.pcs;

/**
 * Constants for Post-Compromise Security (PCS) protocol.
 * <p>
 * PCS provides per-message forward secrecy through symmetric key ratcheting,
 * ensuring that compromise of a message key does not expose past messages.
 */
public interface PcsConstants {

	/**
	 * PCS protocol version. This is incremented when breaking changes
	 * are made to the PCS protocol.
	 */
	int PCS_PROTOCOL_VERSION = 6;

	/**
	 * Maximum number of skipped message keys to store per contact.
	 * This limits memory usage while allowing reasonable out-of-order
	 * message delivery tolerance.
	 */
	int MAX_SKIP = 1000;

	/**
	 * Maximum age in milliseconds for skipped keys before pruning.
	 * Keys older than this are automatically deleted.
	 * 7 days = 7 * 24 * 60 * 60 * 1000 = 604,800,000 ms
	 */
	long MAX_SKIP_AGE_MS = 7L * 24 * 60 * 60 * 1000;

	// ==================== KDF Labels ====================
	// Domain separation labels for BLAKE2b key derivation

	/**
	 * Label for deriving the initial PCS root key from the contact root key.
	 */
	String PCS_ROOT_KDF_LABEL = "org.briarproject.zerion/PCS_ROOT_KDF";

	/**
	 * Label for deriving the next chain key from the current chain key.
	 * Input: chain_key, output: new_chain_key
	 */
	String PCS_CHAIN_KEY_LABEL = "org.briarproject.zerion/PCS_CHAIN_KEY";

	/**
	 * Label for deriving a message key from the current chain key.
	 * Input: chain_key, output: message_key
	 */
	String PCS_MESSAGE_KEY_LABEL = "org.briarproject.zerion/PCS_MESSAGE_KEY";

	/**
	 * Label for Mode 2 KDF_RK operation (DH ratchet step).
	 * Uses BLAKE2b-512 to derive both new root key and chain key.
	 * Input: root_key + dh_output, output: new_root_key (32) + chain_key (32)
	 */
	String PCS_DH_RATCHET_LABEL = "org.briarproject.zerion/PCS_DH_RATCHET";

	/**
	 * Label for DH shared secret derivation in Mode 2.
	 */
	String PCS_DH_SECRET_LABEL = "org.briarproject.zerion/PCS_DH_SECRET";

	// ==================== KDF Input Bytes ====================

	/**
	 * Input byte for chain key derivation (KDF_CK).
	 */
	byte CHAIN_KEY_INPUT = 0x01;

	/**
	 * Input byte for message key derivation (KDF_CK).
	 */
	byte MESSAGE_KEY_INPUT = 0x02;

	// ==================== Header Sizes ====================

	/**
	 * Size of the PCS version field in bytes.
	 */
	int PCS_VERSION_SIZE = 1;

	/**
	 * Size of the PCS flags field in bytes.
	 */
	int PCS_FLAGS_SIZE = 1;

	/**
	 * Size of the message number field in bytes (uint32).
	 */
	int MESSAGE_NUMBER_SIZE = 4;

	/**
	 * Size of the previous chain length field in bytes (uint32).
	 */
	int PREVIOUS_CHAIN_LENGTH_SIZE = 4;

	/**
	 * Minimum PCS header size (Mode 1, symmetric-only).
	 * version (1) + flags (1) + msg_num (4) + prev_chain_len (4) = 10 bytes
	 */
	int PCS_HEADER_MIN_SIZE = PCS_VERSION_SIZE + PCS_FLAGS_SIZE +
			MESSAGE_NUMBER_SIZE + PREVIOUS_CHAIN_LENGTH_SIZE;

	/**
	 * Size of a DH public key for Mode 2 (X25519).
	 */
	int DH_PUBLIC_KEY_SIZE = 32;

	/**
	 * Maximum PCS header size (Mode 2, with DH key).
	 * min_header (10) + dh_public_key (32) = 42 bytes
	 */
	int PCS_HEADER_MAX_SIZE = PCS_HEADER_MIN_SIZE + DH_PUBLIC_KEY_SIZE;

	// ==================== Flag Bits ====================

	/**
	 * Flag bit indicating PCS capability is enabled.
	 */
	byte FLAG_PCS_ENABLED = 0x01;

	/**
	 * Flag bit indicating a DH ratchet key is present (Mode 2).
	 */
	byte FLAG_DH_RATCHET = 0x02;
}
