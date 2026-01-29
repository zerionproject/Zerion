package org.briarproject.briar.conversation.voice;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for VoiceCallCryptoImpl
 * <p>
 * Note: These tests focus on the encode/decode functionality which doesn't
 * require a CryptoComponent. Full integration tests with real CryptoComponent
 * can be found in the integration test suite.
 */
public class VoiceCallCryptoImplTest {

	@Test
	public void testEncodeDecodeVoiceCallKey() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);

		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) i;
		}
		SecretKey originalKey = new SecretKey(keyBytes);

		String encoded = crypto.encodeVoiceCallKey(originalKey);

		assertNotNull(encoded);
		assertTrue(encoded.length() > 0);
		// Hex encoding of 32 bytes = 64 hex characters
		assertEquals(64, encoded.length());
		assertTrue(encoded.matches("[0-9A-Fa-f]+"));

		SecretKey decodedKey = crypto.decodeVoiceCallKey(encoded);
		assertArrayEquals(originalKey.getBytes(), decodedKey.getBytes());
	}

	@Test
	public void testEncodeDecodeAllZeros() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);

		byte[] keyBytes = new byte[32];
		SecretKey originalKey = new SecretKey(keyBytes);

		String encoded = crypto.encodeVoiceCallKey(originalKey);
		SecretKey decodedKey = crypto.decodeVoiceCallKey(encoded);

		assertArrayEquals(originalKey.getBytes(), decodedKey.getBytes());
	}

	@Test
	public void testEncodeDecodeAllOnes() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);

		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) 0xFF;
		}
		SecretKey originalKey = new SecretKey(keyBytes);

		String encoded = crypto.encodeVoiceCallKey(originalKey);
		SecretKey decodedKey = crypto.decodeVoiceCallKey(encoded);

		assertArrayEquals(originalKey.getBytes(), decodedKey.getBytes());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testDecodeInvalidHex() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);
		crypto.decodeVoiceCallKey("!!!invalid hex!!!");
	}

	@Test(expected = IllegalArgumentException.class)
	public void testDecodeEmptyString() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);
		crypto.decodeVoiceCallKey("");
	}

	@Test
	public void testDecodeValidHex() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);

		// Known hex string (32 bytes of sequential values 0-31)
		String knownEncoded =
				"000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f";

		SecretKey key = crypto.decodeVoiceCallKey(knownEncoded);

		assertNotNull(key);
		assertEquals(32, key.getBytes().length);
		for (int i = 0; i < 32; i++) {
			assertEquals((byte) i, key.getBytes()[i]);
		}
	}

	@Test
	public void testEncodeDifferentKeysProduceDifferentOutput() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);

		byte[] keyBytes1 = new byte[32];
		byte[] keyBytes2 = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes1[i] = (byte) i;
			keyBytes2[i] = (byte) (i + 1);
		}
		SecretKey key1 = new SecretKey(keyBytes1);
		SecretKey key2 = new SecretKey(keyBytes2);

		String encoded1 = crypto.encodeVoiceCallKey(key1);
		String encoded2 = crypto.encodeVoiceCallKey(key2);

		assertNotEquals(encoded1, encoded2);
	}

	@Test
	public void testEncodeDeterministic() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);

		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) i;
		}
		SecretKey key = new SecretKey(keyBytes);

		String encoded1 = crypto.encodeVoiceCallKey(key);
		String encoded2 = crypto.encodeVoiceCallKey(key);

		assertEquals(encoded1, encoded2);
	}
}
