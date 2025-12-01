package org.briarproject.bramble.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

/**
 * Enumeration of cryptographic algorithms supported by Zerion.
 * <p>
 * This enum supports the transition from classical (pre-quantum) algorithms
 * to hybrid post-quantum algorithms. During the transition period, both
 * legacy and hybrid modes are supported for backward compatibility.
 * <p>
 * <b>Post-Quantum Security Levels (NIST):</b>
 * <ul>
 *   <li>Level 1: Equivalent to AES-128</li>
 *   <li>Level 3: Equivalent to AES-192</li>
 *   <li>Level 5: Equivalent to AES-256</li>
 * </ul>
 */
@NotNullByDefault
public enum CryptoAlgorithm {

	// ========== KEY AGREEMENT ALGORITHMS ==========

	/**
	 * X25519 (Curve25519) Elliptic Curve Diffie-Hellman.
	 * <p>
	 * Classical algorithm vulnerable to quantum attacks (Shor's algorithm).
	 * Provides 128-bit classical security.
	 * <p>
	 * Key sizes: 32 bytes (public), 32 bytes (private)
	 */
	X25519("X25519", AlgorithmType.KEY_AGREEMENT, false, 32, 32, 0),

	/**
	 * ML-KEM-768 (formerly Kyber-768) Key Encapsulation Mechanism.
	 * <p>
	 * NIST FIPS 203 standardized post-quantum KEM.
	 * Provides NIST Level 3 security (equivalent to AES-192).
	 * <p>
	 * Key sizes: 1,184 bytes (public), 2,400 bytes (private)
	 * Ciphertext: 1,088 bytes
	 */
	ML_KEM_768("ML-KEM-768", AlgorithmType.KEY_AGREEMENT, true, 1184, 2400, 1088),

	/**
	 * Hybrid X25519 + ML-KEM-768 key agreement.
	 * <p>
	 * Combines classical ECDH with post-quantum KEM for defense in depth.
	 * Both algorithms must be broken to compromise the shared secret.
	 * <p>
	 * Key sizes: 1,216 bytes (public), 2,432 bytes (private)
	 */
	HYBRID_X25519_ML_KEM_768("Hybrid-X25519-ML-KEM-768", AlgorithmType.KEY_AGREEMENT, true, 1216, 2432, 1088),

	// ========== SIGNATURE ALGORITHMS ==========

	/**
	 * Ed25519 Edwards-curve Digital Signature Algorithm.
	 * <p>
	 * Classical algorithm vulnerable to quantum attacks (Shor's algorithm).
	 * Provides 128-bit classical security.
	 * <p>
	 * Key sizes: 32 bytes (public), 32 bytes (private/seed)
	 * Signature: 64 bytes
	 */
	ED25519("Ed25519", AlgorithmType.SIGNATURE, false, 32, 32, 64),

	/**
	 * ML-DSA-65 (formerly Dilithium-III) Digital Signature Algorithm.
	 * <p>
	 * NIST FIPS 204 standardized post-quantum signature scheme.
	 * Provides NIST Level 3 security (equivalent to AES-192).
	 * <p>
	 * Key sizes: 1,952 bytes (public), 4,032 bytes (private)
	 * Signature: 3,293 bytes
	 */
	ML_DSA_65("ML-DSA-65", AlgorithmType.SIGNATURE, true, 1952, 4032, 3293),

	/**
	 * Hybrid Ed25519 + ML-DSA-65 signatures.
	 * <p>
	 * Combines classical EdDSA with post-quantum signatures for defense in depth.
	 * Both algorithms must be broken to forge a signature.
	 * <p>
	 * Key sizes: 1,984 bytes (public), 4,064 bytes (private)
	 * Signature: 3,357 bytes (64 + 3,293)
	 */
	HYBRID_ED25519_ML_DSA_65("Hybrid-Ed25519-ML-DSA-65", AlgorithmType.SIGNATURE, true, 1984, 4064, 3357);

	/**
	 * Type of cryptographic algorithm.
	 */
	public enum AlgorithmType {
		KEY_AGREEMENT,
		SIGNATURE
	}

	private final String name;
	private final AlgorithmType type;
	private final boolean postQuantum;
	private final int publicKeyBytes;
	private final int privateKeyBytes;
	private final int outputBytes; // Ciphertext for KEM, signature for DSA

	CryptoAlgorithm(String name, AlgorithmType type, boolean postQuantum,
			int publicKeyBytes, int privateKeyBytes, int outputBytes) {
		this.name = name;
		this.type = type;
		this.postQuantum = postQuantum;
		this.publicKeyBytes = publicKeyBytes;
		this.privateKeyBytes = privateKeyBytes;
		this.outputBytes = outputBytes;
	}

	/**
	 * Returns the algorithm name as used in protocol identification.
	 */
	public String getAlgorithmName() {
		return name;
	}

	/**
	 * Returns the type of this algorithm (key agreement or signature).
	 */
	public AlgorithmType getType() {
		return type;
	}

	/**
	 * Returns true if this algorithm provides post-quantum security.
	 */
	public boolean isPostQuantum() {
		return postQuantum;
	}

	/**
	 * Returns true if this is a hybrid algorithm combining classical and PQ.
	 */
	public boolean isHybrid() {
		return name.startsWith("Hybrid");
	}

	/**
	 * Returns the public key size in bytes.
	 */
	public int getPublicKeyBytes() {
		return publicKeyBytes;
	}

	/**
	 * Returns the private key size in bytes.
	 */
	public int getPrivateKeyBytes() {
		return privateKeyBytes;
	}

	/**
	 * Returns the output size in bytes.
	 * For key agreement: ciphertext size (KEM)
	 * For signatures: signature size
	 */
	public int getOutputBytes() {
		return outputBytes;
	}

	/**
	 * Returns true if this algorithm is suitable for key agreement.
	 */
	public boolean isKeyAgreement() {
		return type == AlgorithmType.KEY_AGREEMENT;
	}

	/**
	 * Returns true if this algorithm is suitable for signatures.
	 */
	public boolean isSignature() {
		return type == AlgorithmType.SIGNATURE;
	}

	/**
	 * Returns the legacy (non-PQ) version of this algorithm.
	 * For hybrid algorithms, returns the classical component.
	 * For classical algorithms, returns itself.
	 */
	public CryptoAlgorithm getLegacyAlgorithm() {
		switch (this) {
			case ML_KEM_768:
			case HYBRID_X25519_ML_KEM_768:
				return X25519;
			case ML_DSA_65:
			case HYBRID_ED25519_ML_DSA_65:
				return ED25519;
			default:
				return this;
		}
	}

	/**
	 * Returns the recommended hybrid algorithm for this algorithm type.
	 */
	public CryptoAlgorithm getHybridAlgorithm() {
		switch (this) {
			case X25519:
			case ML_KEM_768:
			case HYBRID_X25519_ML_KEM_768:
				return HYBRID_X25519_ML_KEM_768;
			case ED25519:
			case ML_DSA_65:
			case HYBRID_ED25519_ML_DSA_65:
				return HYBRID_ED25519_ML_DSA_65;
			default:
				throw new IllegalStateException("Unknown algorithm: " + this);
		}
	}

	/**
	 * Find algorithm by name.
	 */
	public static CryptoAlgorithm fromName(String name) {
		for (CryptoAlgorithm algo : values()) {
			if (algo.name.equals(name)) {
				return algo;
			}
		}
		throw new IllegalArgumentException("Unknown algorithm: " + name);
	}

	/**
	 * Returns the default key agreement algorithm for new contacts.
	 * Currently returns hybrid for maximum security.
	 */
	public static CryptoAlgorithm getDefaultKeyAgreement() {
		return HYBRID_X25519_ML_KEM_768;
	}

	/**
	 * Returns the default signature algorithm for new identities.
	 * Currently returns hybrid for maximum security.
	 */
	public static CryptoAlgorithm getDefaultSignature() {
		return HYBRID_ED25519_ML_DSA_65;
	}

	/**
	 * Returns the legacy key agreement algorithm for backward compatibility.
	 */
	public static CryptoAlgorithm getLegacyKeyAgreement() {
		return X25519;
	}

	/**
	 * Returns the legacy signature algorithm for backward compatibility.
	 */
	public static CryptoAlgorithm getLegacySignature() {
		return ED25519;
	}
}
