package org.briarproject.bramble.api.crypto;

/**
 * Constants for post-quantum cryptographic operations.
 * <p>
 * These constants define the sizes and parameters for ML-KEM-768 (Kyber)
 * and ML-DSA-65 (Dilithium) as standardized in NIST FIPS 203 and FIPS 204.
 */
public interface PostQuantumConstants {

	// ========== ML-KEM-768 (Kyber) Constants ==========
	// NIST FIPS 203 - Module-Lattice-Based Key-Encapsulation Mechanism

	/**
	 * ML-KEM-768 public key size in bytes.
	 */
	int ML_KEM_768_PUBLIC_KEY_BYTES = 1184;

	/**
	 * ML-KEM-768 private key size in bytes.
	 */
	int ML_KEM_768_PRIVATE_KEY_BYTES = 2400;

	/**
	 * ML-KEM-768 ciphertext size in bytes.
	 */
	int ML_KEM_768_CIPHERTEXT_BYTES = 1088;

	/**
	 * ML-KEM-768 shared secret size in bytes.
	 */
	int ML_KEM_768_SHARED_SECRET_BYTES = 32;

	// ========== ML-DSA-65 (Dilithium) Constants ==========
	// NIST FIPS 204 - Module-Lattice-Based Digital Signature Algorithm
	// Note: BouncyCastle 1.82+ uses slightly larger sizes than NIST spec due to encoding

	/**
	 * ML-DSA-65 public key size in bytes.
	 */
	int ML_DSA_65_PUBLIC_KEY_BYTES = 1952;

	/**
	 * ML-DSA-65 private key size in bytes.
	 */
	int ML_DSA_65_PRIVATE_KEY_BYTES = 4032;

	/**
	 * ML-DSA-65 signature size in bytes.
	 * Note: BouncyCastle produces 3309-byte signatures (NIST spec is 3293).
	 * This includes 16 additional bytes for internal context/header.
	 */
	int ML_DSA_65_SIGNATURE_BYTES = 3309;

	// ========== Hybrid Key Constants ==========

	/**
	 * Hybrid agreement public key size: X25519 (32) + ML-KEM-768 (1184).
	 */
	int HYBRID_AGREEMENT_PUBLIC_KEY_BYTES = 32 + ML_KEM_768_PUBLIC_KEY_BYTES;

	/**
	 * Hybrid agreement private key size: X25519 (32) + ML-KEM-768 (2400).
	 */
	int HYBRID_AGREEMENT_PRIVATE_KEY_BYTES = 32 + ML_KEM_768_PRIVATE_KEY_BYTES;

	/**
	 * Hybrid signature public key size: Ed25519 (32) + ML-DSA-65 (1952).
	 */
	int HYBRID_SIGNATURE_PUBLIC_KEY_BYTES = 32 + ML_DSA_65_PUBLIC_KEY_BYTES;

	/**
	 * Hybrid signature private key size: Ed25519 (32) + ML-DSA-65 (4032).
	 */
	int HYBRID_SIGNATURE_PRIVATE_KEY_BYTES = 32 + ML_DSA_65_PRIVATE_KEY_BYTES;

	/**
	 * Hybrid signature size: Ed25519 (64) + ML-DSA-65 (3293).
	 */
	int HYBRID_SIGNATURE_BYTES = 64 + ML_DSA_65_SIGNATURE_BYTES;

	// ========== Protocol Version Constants ==========

	/**
	 * Key agreement protocol version supporting hybrid PQ.
	 */
	byte KEY_AGREEMENT_PROTOCOL_VERSION_HYBRID = 5;

	/**
	 * Contact exchange protocol version supporting hybrid signatures.
	 */
	byte CONTACT_EXCHANGE_PROTOCOL_VERSION_HYBRID = 2;

	/**
	 * Handshake link format version supporting hybrid keys.
	 */
	int HANDSHAKE_LINK_FORMAT_VERSION_HYBRID = 1;

	// ========== Key Type Identifiers ==========

	/**
	 * Key type string for hybrid key agreement.
	 */
	String KEY_TYPE_HYBRID_AGREEMENT = "Hybrid-X25519-ML-KEM-768";

	/**
	 * Key type string for hybrid signatures.
	 */
	String KEY_TYPE_HYBRID_SIGNATURE = "Hybrid-Ed25519-ML-DSA-65";

	// ========== Labels for Key Derivation ==========

	/**
	 * Label for deriving hybrid shared secret from combined secrets.
	 */
	String HYBRID_SHARED_SECRET_LABEL =
			"org.briarproject.bramble.crypto/HYBRID_SHARED_SECRET";

	/**
	 * Label for hybrid key commitment derivation.
	 */
	String HYBRID_COMMITMENT_LABEL =
			"org.briarproject.bramble.crypto/HYBRID_COMMITMENT";
}
