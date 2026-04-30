package org.briarproject.bramble.crypto;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.junit.Test;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class FieldEncryptionTest {

	private final SecureRandom random = new SecureRandom();

	private SecretKey randomKey() {
		byte[] bytes = new byte[SecretKey.LENGTH];
		random.nextBytes(bytes);
		return new SecretKey(bytes);
	}

	@Test
	public void roundTripsString() throws GeneralSecurityException {
		SecretKey key = randomKey();
		byte[] plaintext = "abc123.onion".getBytes();
		byte[] sealed = FieldEncryption.encrypt(key, plaintext);
		byte[] roundTripped = FieldEncryption.decrypt(key, sealed);
		assertArrayEquals(plaintext, roundTripped);
	}

	@Test
	public void roundTripsEmpty() throws GeneralSecurityException {
		SecretKey key = randomKey();
		byte[] sealed = FieldEncryption.encrypt(key, new byte[0]);
		// 12-byte nonce + 0 ciphertext + 16-byte tag = 28 bytes
		assertEquals(28, sealed.length);
		assertArrayEquals(new byte[0], FieldEncryption.decrypt(key, sealed));
	}

	@Test
	public void freshNoncePerCall() throws GeneralSecurityException {
		SecretKey key = randomKey();
		byte[] plaintext = "same input".getBytes();
		byte[] a = FieldEncryption.encrypt(key, plaintext);
		byte[] b = FieldEncryption.encrypt(key, plaintext);
		assertFalse("two encrypts of identical plaintext must produce "
				+ "different sealed bytes (random nonce)",
				Arrays.equals(a, b));
	}

	@Test
	public void rejectsTamperedCiphertext() throws GeneralSecurityException {
		SecretKey key = randomKey();
		byte[] sealed = FieldEncryption.encrypt(key, "data".getBytes());
		// Flip a byte in the ciphertext region (after the 12-byte nonce).
		sealed[15] ^= 0x01;
		try {
			FieldEncryption.decrypt(key, sealed);
			fail("decrypt must reject tampered ciphertext");
		} catch (GeneralSecurityException expected) {
			// pass
		}
	}

	@Test
	public void rejectsWrongKey() throws GeneralSecurityException {
		SecretKey key1 = randomKey();
		SecretKey key2 = randomKey();
		byte[] sealed = FieldEncryption.encrypt(key1, "data".getBytes());
		try {
			FieldEncryption.decrypt(key2, sealed);
			fail("decrypt under a different key must fail tag check");
		} catch (GeneralSecurityException expected) {
			// pass
		}
	}

	@Test
	public void rejectsTooShort() {
		SecretKey key = randomKey();
		// Less than 12 + 16 = 28 bytes of overhead.
		try {
			FieldEncryption.decrypt(key, new byte[16]);
			fail("decrypt must reject input shorter than nonce + tag");
		} catch (GeneralSecurityException expected) {
			// pass
		}
	}

	@Test
	public void sealedFormatLayout() throws GeneralSecurityException {
		SecretKey key = randomKey();
		byte[] plaintext = new byte[100];
		random.nextBytes(plaintext);
		byte[] sealed = FieldEncryption.encrypt(key, plaintext);
		// Combined format: 12-byte nonce + 100-byte ciphertext + 16-byte tag.
		assertEquals(12 + 100 + 16, sealed.length);
	}
}
