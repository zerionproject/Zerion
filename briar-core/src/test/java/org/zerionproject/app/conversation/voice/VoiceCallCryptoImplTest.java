package org.zerionproject.app.conversation.voice;

import org.zerionproject.core.api.crypto.SecretKey;
import org.junit.Test;

import java.security.SecureRandom;
import java.util.Arrays;

import static org.junit.Assert.*;

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

	@Test
	public void testMultiFrameEncryptDecryptRoundTrip() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);
		SecretKey key = makeTestKey();

		byte[] originalKeyBytes = key.getBytes().clone();

		for (int i = 0; i < 100; i++) {
			byte[] plaintext = makeFrame(160, (byte) i);
			byte[] ciphertext = crypto.encryptAudioFrame(plaintext, key);
			byte[] decrypted = crypto.decryptAudioFrame(ciphertext, key);

			assertArrayEquals("Frame " + i + " round-trip failed",
					plaintext, decrypted);
		}

		assertArrayEquals("Key was mutated during encrypt/decrypt",
				originalKeyBytes, key.getBytes());
	}

	@Test
	public void testFrame2DoesNotMatchZeroKeyEncryption() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);
		SecretKey realKey = makeTestKey();

		byte[] frame1 = makeFrame(160, (byte) 0xAA);
		crypto.encryptAudioFrame(frame1, realKey);

		byte[] frame2 = makeFrame(160, (byte) 0xBB);
		byte[] ciphertextReal = crypto.encryptAudioFrame(frame2, realKey);

		SecretKey zeroKey = new SecretKey(new byte[32]);
		byte[] decryptedReal = crypto.decryptAudioFrame(ciphertextReal,
				realKey);
		assertArrayEquals(frame2, decryptedReal);

		try {
			crypto.decryptAudioFrame(ciphertextReal, zeroKey);
			fail("Zero key should not decrypt real-key ciphertext");
		} catch (RuntimeException expected) {

		}
	}

	@Test
	public void testMultiFrameCounterBasedEncrypt() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);
		SecretKey key = makeTestKey();
		byte[] originalKeyBytes = key.getBytes().clone();

		for (long counter = 0; counter < 50; counter++) {
			byte[] plaintext = makeFrame(160, (byte) counter);
			byte[] ciphertext = crypto.encryptAudioFrame(plaintext, key,
					counter);
			assertNotNull("Counter " + counter + " returned null",
					ciphertext);
			assertTrue("Counter " + counter + " ciphertext too short",
					ciphertext.length > plaintext.length);
		}

		assertArrayEquals("Key was mutated during counter-based encrypt",
				originalKeyBytes, key.getBytes());
	}

	@Test
	public void testCounterBasedNonceUniqueness() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);
		SecretKey key = makeTestKey();
		byte[] plaintext = makeFrame(160, (byte) 0x42);

		byte[] ct0 = crypto.encryptAudioFrame(plaintext, key, 0);
		byte[] ct1 = crypto.encryptAudioFrame(plaintext, key, 1);

		assertFalse("Nonce reuse: counter 0 vs 1 produced same ciphertext",
				Arrays.equals(ct0, ct1));
	}

	@Test
	public void testRandomFramesRoundTrip() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);
		SecureRandom rng = new SecureRandom();

		for (int trial = 0; trial < 20; trial++) {
			byte[] keyBytes = new byte[32];
			rng.nextBytes(keyBytes);
			SecretKey key = new SecretKey(keyBytes);

			int frameSize = 80 + rng.nextInt(400);
			byte[] plaintext = new byte[frameSize];
			rng.nextBytes(plaintext);

			byte[] ciphertext = crypto.encryptAudioFrame(plaintext, key);
			byte[] decrypted = crypto.decryptAudioFrame(ciphertext, key);

			assertArrayEquals("Trial " + trial + " round-trip failed",
					plaintext, decrypted);
		}
	}

	@Test
	public void testAliceBobMultiFrameSymmetry() {
		VoiceCallCryptoImpl crypto = new VoiceCallCryptoImpl(null);

		byte[] aliceKeyBytes = new byte[32];
		byte[] bobKeyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			aliceKeyBytes[i] = (byte) (i + 1);
			bobKeyBytes[i] = (byte) (i + 1);
		}
		SecretKey aliceTx = new SecretKey(aliceKeyBytes);
		SecretKey bobRx = new SecretKey(bobKeyBytes);

		for (int i = 0; i < 50; i++) {
			byte[] plaintext = makeFrame(160, (byte) i);
			byte[] ciphertext = crypto.encryptAudioFrame(plaintext, aliceTx);
			byte[] decrypted = crypto.decryptAudioFrame(ciphertext, bobRx);
			assertArrayEquals("Frame " + i + " Alice->Bob failed",
					plaintext, decrypted);
		}

		assertArrayEquals(aliceKeyBytes, aliceTx.getBytes());
		assertArrayEquals(bobKeyBytes, bobRx.getBytes());
	}

	private static SecretKey makeTestKey() {
		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) (i + 0x10);
		}
		return new SecretKey(keyBytes);
	}

	private static byte[] makeFrame(int size, byte fill) {
		byte[] frame = new byte[size];
		Arrays.fill(frame, fill);
		return frame;
	}
}
