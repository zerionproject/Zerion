package org.zerionproject.core.api.crypto;

public interface CryptoConstants {

	int MAX_AGREEMENT_PUBLIC_KEY_BYTES = 32;

	String KEY_TYPE_AGREEMENT = "Curve25519";

	int MAX_SIGNATURE_PUBLIC_KEY_BYTES = 32;

	String KEY_TYPE_SIGNATURE = "Ed25519";

	int MAX_SIGNATURE_BYTES = 64;

	int MAC_BYTES = SecretKey.LENGTH;

}
