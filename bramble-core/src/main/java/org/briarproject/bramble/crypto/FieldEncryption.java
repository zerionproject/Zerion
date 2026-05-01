package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM field-level encryption for sensitive at-rest values that
 * sit inside the SQLCipher-encrypted database. Used by B.4 onion
 * rotation to encrypt {@code b4.*} state in {@code SettingsManager}
 * (pre-announced peer onions, rotation-state flags, alice's pending
 * keypair metadata) — anything that, if recovered from disk forensically
 * after the user is unlocked, would re-link them to a peer or expose
 * a not-yet-published rotation.
 *
 * <p>Wire-shape parity with iOS: output bytes are
 * {@code [12-byte nonce || ciphertext || 16-byte tag]}, the same
 * combined form as {@code AES.GCM.SealedBox.combined} in CryptoKit.
 * That makes a future cross-platform-restore (out of scope for v1.5)
 * trivially compatible.
 *
 * <p>Key: the existing SQLCipher master key
 * ({@link org.briarproject.bramble.api.account.AccountManager#getDatabaseKey()}).
 * No new key material; reusing the database key keeps the threat model
 * symmetric with iOS — both treat unlock-time-in-heap as the trust
 * boundary, not Keystore-vs-not.
 */
@NotNullByDefault
public final class FieldEncryption {

	private static final String ALGORITHM = "AES/GCM/NoPadding";
	private static final int NONCE_LEN = 12;
	private static final int TAG_LEN_BITS = 128;

	private static final ThreadLocal<SecureRandom> RNG =
			ThreadLocal.withInitial(SecureRandom::new);

	private FieldEncryption() {
	}

	/**
	 * Seal {@code plaintext} under {@code key}. Returns
	 * {@code [nonce || ciphertext || tag]} — total length is
	 * {@code 12 + plaintext.length + 16}.
	 *
	 * <p>A fresh random 96-bit nonce is generated per call. With a
	 * 256-bit key and uniform-random nonces, the birthday bound on
	 * GCM nonce reuse is ~2^32 invocations per key — well above
	 * anything the field-encryption use-case will hit, even across a
	 * device's whole lifetime.
	 */
	public static byte[] encrypt(SecretKey key, byte[] plaintext)
			throws GeneralSecurityException {
		byte[] nonce = new byte[NONCE_LEN];
		RNG.get().nextBytes(nonce);
		Cipher cipher = Cipher.getInstance(ALGORITHM);
		SecretKeySpec spec = new SecretKeySpec(key.getBytes(), "AES");
		try {
			cipher.init(Cipher.ENCRYPT_MODE, spec,
					new GCMParameterSpec(TAG_LEN_BITS, nonce));
			byte[] ciphertextWithTag = cipher.doFinal(plaintext);
			ByteBuffer out = ByteBuffer.allocate(
					NONCE_LEN + ciphertextWithTag.length);
			out.put(nonce);
			out.put(ciphertextWithTag);
			return out.array();
		} finally {
		}
	}

	/**
	 * Open a sealed value produced by {@link #encrypt}. Throws
	 * {@link GeneralSecurityException} on tag mismatch (tampering or
	 * wrong key) or if the input is shorter than the nonce + tag
	 * overhead.
	 *
	 * <p>Callers that want a "is this row still plaintext from a
	 * pre-v5.1 install?" probe can catch the exception and fall back —
	 * the AES-GCM tag verifies before any plaintext is returned, so a
	 * legacy plaintext row will reliably fail open.
	 */
	public static byte[] decrypt(SecretKey key, byte[] sealed)
			throws GeneralSecurityException {
		if (sealed.length < NONCE_LEN + (TAG_LEN_BITS / 8)) {
			throw new GeneralSecurityException("sealed too short");
		}
		byte[] nonce = Arrays.copyOfRange(sealed, 0, NONCE_LEN);
		byte[] ciphertextWithTag =
				Arrays.copyOfRange(sealed, NONCE_LEN, sealed.length);
		Cipher cipher = Cipher.getInstance(ALGORITHM);
		SecretKeySpec spec = new SecretKeySpec(key.getBytes(), "AES");
		cipher.init(Cipher.DECRYPT_MODE, spec,
				new GCMParameterSpec(TAG_LEN_BITS, nonce));
		return cipher.doFinal(ciphertextWithTag);
	}
}
