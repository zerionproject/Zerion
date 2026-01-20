package org.briarproject.bramble.api.contact;

import java.util.regex.Pattern;

import static org.briarproject.bramble.api.crypto.PostQuantumConstants.HYBRID_AGREEMENT_PUBLIC_KEY_BYTES;

public interface HandshakeLinkConstants {

	// ==================== Classical Format (Version 0) ====================

	/**
	 * Version 0: Classical X25519 key exchange (32-byte keys).
	 * Compatible with standard Briar clients.
	 */
	int FORMAT_VERSION_CLASSICAL = 0;

	/**
	 * The length of a base32-encoded classical handshake link in bytes,
	 * excluding the 'zerion://' prefix.
	 */
	int BASE32_LINK_BYTES_CLASSICAL = 53;

	/**
	 * The length of a raw classical handshake link in bytes, before base32 encoding.
	 * Format: version (1 byte) + X25519 public key (32 bytes) = 33 bytes
	 */
	int RAW_LINK_BYTES_CLASSICAL = 33;

	// ==================== Hybrid PQ Format (Version 1) ====================

	/**
	 * Version 1: Hybrid PQ key exchange using commitment scheme.
	 * <p>
	 * Since full hybrid keys (1,216 bytes) would make links too long,
	 * version 1 uses a commitment-based staged exchange:
	 * <ol>
	 *   <li>Link contains: version + BLAKE2b-256 commitment to full hybrid key</li>
	 *   <li>Full hybrid keys are exchanged over the Tor connection</li>
	 *   <li>Recipient verifies commitment matches received key</li>
	 * </ol>
	 * This keeps links short while enabling post-quantum security.
	 */
	int FORMAT_VERSION_HYBRID = 1;

	/**
	 * The length of a base32-encoded hybrid handshake link in bytes.
	 * Contains a commitment (hash) of the full hybrid key, keeping the
	 * link the same size as classical format.
	 */
	int BASE32_LINK_BYTES_HYBRID = 53;

	/**
	 * The length of a raw hybrid handshake link in bytes, before base32 encoding.
	 * Format: version (1 byte) + BLAKE2b-256 commitment (32 bytes) = 33 bytes
	 */
	int RAW_LINK_BYTES_HYBRID = 33;

	/**
	 * The full hybrid public key size (X25519 + ML-KEM-768).
	 * This is exchanged over the secure channel, not in QR codes.
	 */
	int HYBRID_PUBLIC_KEY_BYTES = HYBRID_AGREEMENT_PUBLIC_KEY_BYTES; // 1,216 bytes

	// ==================== Current Default ====================

	/**
	 * The current version of the handshake link format.
	 * Set to FORMAT_VERSION_HYBRID to enable post-quantum security.
	 */
	int FORMAT_VERSION = FORMAT_VERSION_HYBRID;

	/**
	 * The length of a base32-encoded handshake link in bytes, excluding the
	 * 'zerion://' prefix.
	 */
	int BASE32_LINK_BYTES = BASE32_LINK_BYTES_HYBRID;

	/**
	 * The length of a raw handshake link in bytes, before base32 encoding.
	 */
	int RAW_LINK_BYTES = RAW_LINK_BYTES_HYBRID;

	/**
	 * Regular expression for matching handshake links.
	 * Accepts both 'zerion://' and 'briar://' prefixes for interoperability,
	 * as well as bare base32 strings without any prefix.
	 * Also handles optional query parameters (e.g., ?foo=bar).
	 */
	Pattern LINK_REGEX =
			Pattern.compile("(?:(?:zerion|briar)://)?([a-z2-7]{" + BASE32_LINK_BYTES + "})(?:\\?.*)?");

	// ==================== Labels ====================

	/**
	 * Label for hashing handshake public keys to calculate their identifiers.
	 */
	String ID_LABEL = "org.briarproject.bramble/HANDSHAKE_KEY_ID";

	/**
	 * Label for creating commitments to hybrid public keys.
	 */
	String HYBRID_COMMITMENT_LABEL =
			"org.briarproject.bramble/HYBRID_KEY_COMMITMENT";
}
