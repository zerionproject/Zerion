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
		// Create test implementation (we'll test encode/decode only)
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);

		// Create a test key
		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) i;
		}
		SecretKey originalKey = new SecretKey(keyBytes);

		// Encode
		String encoded = crypto.encodeVoiceCallKey(originalKey);

		// Verify encoded is base64
		assertNotNull(encoded);
		assertTrue(encoded.length() > 0);
		// Base64 encoding of 32 bytes should be 44 characters (with padding)
		assertEquals(44, encoded.length());
		// Should only contain base64 characters
		assertTrue(encoded.matches("[A-Za-z0-9+/=]+"));

		// Decode
		SecretKey decodedKey = crypto.decodeVoiceCallKey(encoded);

		// Verify decoded matches original
		assertArrayEquals(originalKey.getBytes(), decodedKey.getBytes());
	}

	@Test
	public void testEncodeDecodeAllZeros() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);

		// Create key with all zeros
		byte[] keyBytes = new byte[32];
		SecretKey originalKey = new SecretKey(keyBytes);

		// Encode and decode
		String encoded = crypto.encodeVoiceCallKey(originalKey);
		SecretKey decodedKey = crypto.decodeVoiceCallKey(encoded);

		// Verify round-trip
		assertArrayEquals(originalKey.getBytes(), decodedKey.getBytes());
	}

	@Test
	public void testEncodeDecodeAllOnes() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);

		// Create key with all 0xFF
		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) 0xFF;
		}
		SecretKey originalKey = new SecretKey(keyBytes);

		// Encode and decode
		String encoded = crypto.encodeVoiceCallKey(originalKey);
		SecretKey decodedKey = crypto.decodeVoiceCallKey(encoded);

		// Verify round-trip
		assertArrayEquals(originalKey.getBytes(), decodedKey.getBytes());
	}

	@Test(expected = IllegalArgumentException.class)
	public void testDecodeInvalidBase64() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);
		// Try to decode invalid base64
		crypto.decodeVoiceCallKey("!!!invalid base64!!!");
	}

	@Test(expected = IllegalArgumentException.class)
	public void testDecodeEmptyString() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);
		// Try to decode empty string
		crypto.decodeVoiceCallKey("");
	}

	@Test
	public void testDecodeValidBase64() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);

		// Known base64 string (32 bytes of sequential values 0-31)
		String knownEncoded = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";

		// Decode
		SecretKey key = crypto.decodeVoiceCallKey(knownEncoded);

		// Verify
		assertNotNull(key);
		assertEquals(32, key.getBytes().length);
		for (int i = 0; i < 32; i++) {
			assertEquals((byte) i, key.getBytes()[i]);
		}
	}

	@Test
	public void testEncodeDifferentKeysProduceDifferentOutput() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);

		// Create two different keys
		byte[] keyBytes1 = new byte[32];
		byte[] keyBytes2 = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes1[i] = (byte) i;
			keyBytes2[i] = (byte) (i + 1);
		}
		SecretKey key1 = new SecretKey(keyBytes1);
		SecretKey key2 = new SecretKey(keyBytes2);

		// Encode both
		String encoded1 = crypto.encodeVoiceCallKey(key1);
		String encoded2 = crypto.encodeVoiceCallKey(key2);

		// Verify they produce different encodings
		assertNotEquals(encoded1, encoded2);
	}

	@Test
	public void testEncodeDeterministic() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);

		// Create a key
		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) i;
		}
		SecretKey key = new SecretKey(keyBytes);

		// Encode multiple times
		String encoded1 = crypto.encodeVoiceCallKey(key);
		String encoded2 = crypto.encodeVoiceCallKey(key);

		// Verify encoding is deterministic
		assertEquals(encoded1, encoded2);
	}
}
