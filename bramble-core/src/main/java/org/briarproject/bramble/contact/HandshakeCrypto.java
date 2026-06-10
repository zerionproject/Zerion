package org.briarproject.bramble.contact;

import org.briarproject.bramble.api.crypto.HybridEncapsulationResult;
import org.briarproject.bramble.api.crypto.KeyPair;
import org.briarproject.bramble.api.crypto.PublicKey;
import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

@NotNullByDefault
interface HandshakeCrypto {

	KeyPair generateEphemeralKeyPair();

	KeyPair generateHybridEphemeralKeyPair();

	@Deprecated
	SecretKey deriveMasterKey_0_0(PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, boolean alice)
			throws GeneralSecurityException;

	SecretKey deriveMasterKey_0_1(PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, boolean alice)
			throws GeneralSecurityException;

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
