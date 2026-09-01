package com.professor.zerion.android.vault.crypto;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.Arrays;

public class VaultCryptoTest {

	private final VaultCrypto crypto = new VaultCrypto();
	private final byte[] aad = "vault-item".getBytes();

	private byte[] key() {
		return crypto.generateKey();
	}

	@Test
	public void roundTripsAuthenticatedEncryption() {
		byte[] key = key();
		byte[] plain = "a bitcoin seed lives here".getBytes();
		VaultCrypto.EncryptedData enc = crypto.encrypt(plain, key, aad);
		assertArrayEquals(plain, crypto.decrypt(enc, key, aad));
	}

	@Test
	public void freshNonceEveryEncryption() {
		byte[] key = key();
		byte[] plain = "same plaintext".getBytes();
		VaultCrypto.EncryptedData a = crypto.encrypt(plain, key, aad);
		VaultCrypto.EncryptedData b = crypto.encrypt(plain, key, aad);
		assertEquals(12, a.nonce.length);
		assertFalse(Arrays.equals(a.nonce, b.nonce));
		assertFalse(Arrays.equals(a.ciphertext, b.ciphertext));
	}

	@Test
	public void corruptedCiphertextFailsClosed() {
		byte[] key = key();
		VaultCrypto.EncryptedData enc = crypto.encrypt("x".getBytes(), key, aad);
		enc.ciphertext[0] ^= 0x01;
		assertThrows(RuntimeException.class, () -> crypto.decrypt(enc, key, aad));
	}

	@Test
	public void wrongKeyFailsClosed() {
		VaultCrypto.EncryptedData enc = crypto.encrypt("x".getBytes(), key(),
				aad);
		assertThrows(RuntimeException.class,
				() -> crypto.decrypt(enc, key(), aad));
	}

	@Test
	public void wrongAadFailsClosed() {
		byte[] key = key();
		VaultCrypto.EncryptedData enc = crypto.encrypt("x".getBytes(), key, aad);
		assertThrows(RuntimeException.class,
				() -> crypto.decrypt(enc, key, "different".getBytes()));
	}

	@Test
	public void rejectsNon256BitKey() {
		assertThrows(IllegalArgumentException.class,
				() -> crypto.encrypt("x".getBytes(), new byte[16], aad));
	}
}
