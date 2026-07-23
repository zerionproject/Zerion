package org.zerionproject.core.crypto;

import org.zerionproject.core.api.crypto.SecretKey;
import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@NotNullByDefault
public final class FieldEncryption {

	private static final String ALGORITHM = "AES/GCM/NoPadding";
	private static final int NONCE_LEN = 12;
	private static final int TAG_LEN_BITS = 128;

	private static final ThreadLocal<SecureRandom> RNG =
			ThreadLocal.withInitial(SecureRandom::new);

	private FieldEncryption() {
	}

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
