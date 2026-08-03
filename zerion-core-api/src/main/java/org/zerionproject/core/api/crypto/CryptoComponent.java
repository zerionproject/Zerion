package org.zerionproject.core.api.crypto;

import org.zerionproject.core.api.UniqueId;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.annotation.Nullable;

@NotNullByDefault
public interface CryptoComponent {

	UniqueId generateUniqueId();

	SecretKey generateSecretKey();

	SecureRandom getSecureRandom();

	KeyPair generateAgreementKeyPair();

	KeyParser getAgreementKeyParser();

	KeyPair generateSignatureKeyPair();

	KeyParser getSignatureKeyParser();

	KeyPair generateHybridAgreementKeyPair();

	KeyParser getHybridAgreementKeyParser();

	KeyPair generateHybridSignatureKeyPair();

	KeyParser getHybridSignatureKeyParser();

	byte[] hybridSign(String label, byte[] toSign, PrivateKey privateKey)
			throws GeneralSecurityException;

	boolean verifyHybridSignature(byte[] signature, String label, byte[] signed,
			PublicKey publicKey) throws GeneralSecurityException;

	HybridEncapsulationResult hybridEncapsulate(PublicKey theirPublicKey)
			throws GeneralSecurityException;

	SecretKey deriveHybridSharedSecret(String label, PublicKey theirPublicKey,
			KeyPair ourKeyPair, byte[] kemCiphertext, byte[]... inputs)
			throws GeneralSecurityException;

	SecretKey deriveHybridSharedSecretAsResponder(String label,
			PublicKey theirPublicKey, KeyPair ourKeyPair, byte[] kemSecret,
			byte[]... inputs) throws GeneralSecurityException;

	SecretKey deriveHybridSharedSecretFs(String label,
			PublicKey theirStaticPublicKey, PublicKey theirEphemeralPublicKey,
			KeyPair ourStaticKeyPair, KeyPair ourEphemeralKeyPair,
			byte[] kemCiphertext, byte[]... inputs)
			throws GeneralSecurityException;

	SecretKey deriveHybridSharedSecretFsAsResponder(String label,
			PublicKey theirStaticPublicKey, PublicKey theirEphemeralPublicKey,
			KeyPair ourStaticKeyPair, KeyPair ourEphemeralKeyPair,
			byte[] kemSecret, byte[]... inputs)
			throws GeneralSecurityException;

	SecretKey deriveKey(String label, SecretKey k, byte[]... inputs);

	SecretKey deriveSharedSecret(String label, PublicKey theirPublicKey,
			KeyPair ourKeyPair, byte[]... inputs)
			throws GeneralSecurityException;

	@Deprecated
	SecretKey deriveSharedSecretBadly(String label,
			PublicKey theirStaticPublicKey, PublicKey theirEphemeralPublicKey,
			KeyPair ourStaticKeyPair, KeyPair ourEphemeralKeyPair,
			boolean alice, byte[]... inputs)
			throws GeneralSecurityException;

	SecretKey deriveSharedSecret(String label, PublicKey theirStaticPublicKey,
			PublicKey theirEphemeralPublicKey, KeyPair ourStaticKeyPair,
			KeyPair ourEphemeralKeyPair, boolean alice, byte[]... inputs)
			throws GeneralSecurityException;

	byte[] sign(String label, byte[] toSign, PrivateKey privateKey)
			throws GeneralSecurityException;

	boolean verifySignature(byte[] signature, String label, byte[] signed,
			PublicKey publicKey) throws GeneralSecurityException;

	byte[] hash(String label, byte[]... inputs);

	byte[] mac(String label, SecretKey macKey, byte[]... inputs);

	boolean verifyMac(byte[] mac, String label, SecretKey macKey,
			byte[]... inputs);

	byte[] encryptWithPassword(byte[] plaintext, char[] password,
			@Nullable KeyStrengthener keyStrengthener);

	byte[] decryptWithPassword(byte[] ciphertext, char[] password,
			@Nullable KeyStrengthener keyStrengthener)
			throws DecryptionException;

	boolean isEncryptedWithStrengthenedKey(byte[] ciphertext);

	boolean isEncryptedWithLegacyKdf(byte[] ciphertext);

	String asciiArmour(byte[] b, int lineLength);

	String encodeOnion(byte[] publicKey);

}
