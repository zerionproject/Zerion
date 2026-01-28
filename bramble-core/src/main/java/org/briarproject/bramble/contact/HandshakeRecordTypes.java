package org.briarproject.bramble.contact;

/**
 * Record types for the handshake protocol.
 */
interface HandshakeRecordTypes {

	byte RECORD_TYPE_EPHEMERAL_PUBLIC_KEY = 0;

	byte RECORD_TYPE_PROOF_OF_OWNERSHIP = 1;

	byte RECORD_TYPE_MINOR_VERSION = 2;

	/**
	 * Record type for exchanging full hybrid static public keys.
	 * Used in PQ handshakes where the link contains only a commitment.
	 * The actual 1,216-byte hybrid key is sent in this record.
	 */
	byte RECORD_TYPE_HYBRID_STATIC_KEY = 3;

	/**
	 * Record type for exchanging KEM ciphertext during hybrid key agreement.
	 * Alice sends this to Bob after encapsulating to Bob's hybrid public key.
	 * The ciphertext is 1,088 bytes (ML-KEM-768).
	 */
	byte RECORD_TYPE_KEM_CIPHERTEXT = 4;

	/**
	 * Record type for advertising Mode 3 (Triple Ratchet) capability.
	 * Sent during hybrid handshakes when MODE3_ENABLED is true.
	 * The payload is a single byte: 0x01 indicates Mode 3 support.
	 */
	byte RECORD_TYPE_MODE3_CAPABILITY = 5;
}
