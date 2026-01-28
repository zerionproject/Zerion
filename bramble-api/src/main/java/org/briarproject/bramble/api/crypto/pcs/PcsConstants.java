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

	/**
	 * Flag bit indicating Mode 3 PQ ratchet is active.
	 */
	byte FLAG_PQ_ENABLED = 0x04;

	/**
	 * Flag bit indicating PQ chunk data is present in this message.
	 */
	byte FLAG_PQ_CHUNK = 0x08;

	// ==================== Mode 3 (Triple Ratchet) Constants ====================

	/**
	 * Mode 3 (Triple Ratchet) feature flag.
	 * When true, Zerion-to-Zerion contacts use ML-KEM-768 post-quantum ratchet.
	 *
	 * NOTE: Now enabled with symmetric send/receive state enrichment.
	 */
	boolean MODE3_ENABLED = true;

	/**
	 * ML-KEM-768 encapsulation key (public key) size in bytes.
	 */
	int MLKEM_ENCAPSULATION_KEY_SIZE = 1184;

	/**
	 * ML-KEM-768 decapsulation key (private key) size in bytes.
	 */
	int MLKEM_DECAPSULATION_KEY_SIZE = 2400;

	/**
	 * ML-KEM-768 ciphertext size in bytes.
	 */
	int MLKEM_CIPHERTEXT_SIZE = 1088;

	/**
	 * ML-KEM-768 shared secret size in bytes.
	 */
	int MLKEM_SHARED_SECRET_SIZE = 32;

	/**
	 * Size of the EK seed (32 bytes) + SHA3-256 hash (32 bytes).
	 */
	int MLKEM_EK_SEED_SIZE = 32;

	/**
	 * Size of the EK seed hash for verification.
	 */
	int MLKEM_EK_HASH_SIZE = 32;

	/**
	 * Size of the EK seed + hash combined (first chunk).
	 */
	int MLKEM_EK_SEED_TOTAL_SIZE = MLKEM_EK_SEED_SIZE + MLKEM_EK_HASH_SIZE;

	/**
	 * Size of the EK vector (encapsulation key minus seed).
	 */
	int MLKEM_EK_VECTOR_SIZE = MLKEM_ENCAPSULATION_KEY_SIZE - MLKEM_EK_SEED_SIZE;

	/**
	 * PQ chunk size for transmission. Optimized for Tor (fits in cell).
	 */
	int PQ_CHUNK_SIZE = 256;

	/**
	 * Number of chunks required for EK vector.
	 */
	int PQ_EK_VECTOR_CHUNKS = (MLKEM_EK_VECTOR_SIZE + PQ_CHUNK_SIZE - 1) / PQ_CHUNK_SIZE;

	/**
	 * Number of chunks required for ciphertext.
	 */
	int PQ_CIPHERTEXT_CHUNKS = (MLKEM_CIPHERTEXT_SIZE + PQ_CHUNK_SIZE - 1) / PQ_CHUNK_SIZE;

	/**
	 * Total chunks per PQ epoch (1 seed + EK vector chunks + CT chunks).
	 */
	int PQ_TOTAL_CHUNKS_PER_EPOCH = 1 + PQ_EK_VECTOR_CHUNKS + PQ_CIPHERTEXT_CHUNKS;

	/**
	 * Message threshold for triggering new PQ epoch.
	 */
	int PQ_EPOCH_MESSAGE_THRESHOLD = 25;

	/**
	 * Time threshold in milliseconds for triggering new PQ epoch (24 hours).
	 */
	long PQ_EPOCH_TIME_THRESHOLD_MS = 24L * 60 * 60 * 1000;

	// ==================== Mode 3 KDF Labels ====================

	/**
	 * Label for updating root key with PQ secret.
	 */
	String PCS_PQ_ROOT_UPDATE_LABEL = "org.briarproject.zerion/PCS_PQ_ROOT_UPDATE";

	/**
	 * Label for hybrid root key derivation (DH + PQ).
	 */
	String PCS_HYBRID_ROOT_LABEL = "org.briarproject.zerion/PCS_HYBRID_ROOT";

	/**
	 * Label for hybrid chain key derivation.
	 */
	String PCS_HYBRID_CHAIN_LABEL = "org.briarproject.zerion/PCS_HYBRID_CHAIN";

	// ==================== Mode 3 Header Sizes ====================

	/**
	 * Size of PQ epoch field in bytes (uint32).
	 */
	int PQ_EPOCH_SIZE = 4;

	/**
	 * Size of PQ chunk header (type + index + length).
	 */
	int PQ_CHUNK_HEADER_SIZE = 4;

	/**
	 * Chunk type: EK seed + hash.
	 */
	byte PQ_CHUNK_TYPE_EK_SEED = 0x00;

	/**
	 * Chunk type: EK vector.
	 */
	byte PQ_CHUNK_TYPE_EK_VEC = 0x01;

	/**
	 * Chunk type: Ciphertext.
	 */
	byte PQ_CHUNK_TYPE_CT = 0x02;

	/**
	 * Mode 3 minimum header size (Mode 2 header + PQ epoch).
	 */
	int PCS_MODE3_HEADER_MIN_SIZE = PCS_HEADER_MAX_SIZE + PQ_EPOCH_SIZE;

	/**
	 * Mode 3 maximum header size (with PQ chunk).
	 */
	int PCS_MODE3_HEADER_MAX_SIZE = PCS_MODE3_HEADER_MIN_SIZE +
			PQ_CHUNK_HEADER_SIZE + PQ_CHUNK_SIZE;
}
