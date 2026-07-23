package org.zerionproject.core.contact;

import org.zerionproject.core.api.crypto.HybridEncapsulationResult;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;
import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

@NotNullByDefault
public interface HandshakeCrypto {

	KeyPair generateEphemeralKeyPair();

	KeyPair generateHybridEphemeralKeyPair();

	HybridEncapsulationResult hybridEncapsulate(PublicKey theirPublicKey)
			throws GeneralSecurityException;

	SecretKey deriveHybridMasterKey(PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, byte[] kemCiphertext,
			byte[] kemSecret, boolean alice)
			throws GeneralSecurityException;

	SecretKey deriveHybridMasterKeyFs(PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, byte[] kemCiphertext,
			byte[] kemSecret, boolean alice, byte ourMinor, byte theirMinor)
			throws GeneralSecurityException;

	byte[] proveOwnership(SecretKey masterKey, boolean alice);

	boolean verifyOwnership(SecretKey masterKey, boolean alice, byte[] proof);
}
