package org.zerionproject.core.api.crypto;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public enum CryptoAlgorithm {

	X25519("X25519", AlgorithmType.KEY_AGREEMENT, false, 32, 32, 0),

	ML_KEM_768("ML-KEM-768", AlgorithmType.KEY_AGREEMENT, true, 1184, 2400, 1088),

	HYBRID_X25519_ML_KEM_768("Hybrid-X25519-ML-KEM-768", AlgorithmType.KEY_AGREEMENT, true, 1216, 2432, 1088),

	ED25519("Ed25519", AlgorithmType.SIGNATURE, false, 32, 32, 64),

	ML_DSA_65("ML-DSA-65", AlgorithmType.SIGNATURE, true, 1952, 4032, 3293),

	HYBRID_ED25519_ML_DSA_65("Hybrid-Ed25519-ML-DSA-65", AlgorithmType.SIGNATURE, true, 1984, 4064, 3357);

	public enum AlgorithmType {
		KEY_AGREEMENT,
		SIGNATURE
	}

	private final String name;
	private final AlgorithmType type;
	private final boolean postQuantum;
	private final int publicKeyBytes;
	private final int privateKeyBytes;
	private final int outputBytes;

	CryptoAlgorithm(String name, AlgorithmType type, boolean postQuantum,
			int publicKeyBytes, int privateKeyBytes, int outputBytes) {
		this.name = name;
		this.type = type;
		this.postQuantum = postQuantum;
		this.publicKeyBytes = publicKeyBytes;
		this.privateKeyBytes = privateKeyBytes;
		this.outputBytes = outputBytes;
	}

	public String getAlgorithmName() {
		return name;
	}

	public AlgorithmType getType() {
		return type;
	}

	public boolean isPostQuantum() {
		return postQuantum;
	}

	public boolean isHybrid() {
		return name.startsWith("Hybrid");
	}

	public int getPublicKeyBytes() {
		return publicKeyBytes;
	}

	public int getPrivateKeyBytes() {
		return privateKeyBytes;
	}

	public int getOutputBytes() {
		return outputBytes;
	}

	public boolean isKeyAgreement() {
		return type == AlgorithmType.KEY_AGREEMENT;
	}

	public boolean isSignature() {
		return type == AlgorithmType.SIGNATURE;
	}

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

	public static CryptoAlgorithm fromName(String name) {
		for (CryptoAlgorithm algo : values()) {
			if (algo.name.equals(name)) {
				return algo;
			}
		}
		throw new IllegalArgumentException("Unknown algorithm: " + name);
	}

	public static CryptoAlgorithm getDefaultKeyAgreement() {
		return HYBRID_X25519_ML_KEM_768;
	}

	public static CryptoAlgorithm getDefaultSignature() {
		return HYBRID_ED25519_ML_DSA_65;
	}

	public static CryptoAlgorithm getLegacyKeyAgreement() {
		return X25519;
	}

	public static CryptoAlgorithm getLegacySignature() {
		return ED25519;
	}
}
