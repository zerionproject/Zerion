package org.briarproject.briar.conversation.voice;

import org.briarproject.bramble.api.crypto.SecretKey;
import org.briarproject.bramble.api.rendezvous.KeyMaterialSource;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for VoiceCallKeyMaterialSource
 */
public class VoiceCallKeyMaterialSourceTest {

	@Test
	public void testGeneratesKeyMaterial() {
		// Create source with test key
		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) i;
		}
		SecretKey sourceKey = new SecretKey(keyBytes);
		KeyMaterialSource source = new VoiceCallKeyMaterialSource(sourceKey);

		// Get key material
		byte[] material = source.getKeyMaterial(64);

		// Verify
		assertNotNull(material);
		assertEquals(64, material.length);
	}

	@Test
	public void testDeterministicOutput() {
		// Create two sources with same key
		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) i;
		}
		SecretKey sourceKey = new SecretKey(keyBytes);

		KeyMaterialSource source1 = new VoiceCallKeyMaterialSource(sourceKey);
		KeyMaterialSource source2 = new VoiceCallKeyMaterialSource(sourceKey);

		// Get material from both
		byte[] material1 = source1.getKeyMaterial(64);
		byte[] material2 = source2.getKeyMaterial(64);

		// Verify both produce same output
		assertArrayEquals("Same key should produce same key material",
				material1, material2);
	}

	@Test
	public void testProgressiveOutput() {
		// Create source
		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) i;
		}
		SecretKey sourceKey = new SecretKey(keyBytes);
		KeyMaterialSource source = new VoiceCallKeyMaterialSource(sourceKey);

		// Get multiple chunks
		byte[] chunk1 = source.getKeyMaterial(32);
		byte[] chunk2 = source.getKeyMaterial(32);

		// Verify chunks are different (counter increments)
		assertFalse("Consecutive calls should produce different material",
				java.util.Arrays.equals(chunk1, chunk2));
	}

	@Test
	public void testDifferentKeyProducesDifferentOutput() {
		// Create two sources with different keys
		byte[] keyBytes1 = new byte[32];
		byte[] keyBytes2 = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes1[i] = (byte) i;
			keyBytes2[i] = (byte) (i + 1);
		}

		SecretKey sourceKey1 = new SecretKey(keyBytes1);
		SecretKey sourceKey2 = new SecretKey(keyBytes2);

		KeyMaterialSource source1 = new VoiceCallKeyMaterialSource(sourceKey1);
		KeyMaterialSource source2 = new VoiceCallKeyMaterialSource(sourceKey2);

		// Get material from both
		byte[] material1 = source1.getKeyMaterial(64);
		byte[] material2 = source2.getKeyMaterial(64);

		// Verify they produce different output
		assertFalse("Different keys should produce different material",
				java.util.Arrays.equals(material1, material2));
	}

	@Test
	public void testLargeOutput() {
		// Create source
		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) i;
		}
		SecretKey sourceKey = new SecretKey(keyBytes);
		KeyMaterialSource source = new VoiceCallKeyMaterialSource(sourceKey);

		// Get large amount of key material (more than one HMAC block)
		byte[] material = source.getKeyMaterial(256);

		// Verify
		assertNotNull(material);
		assertEquals(256, material.length);

		// Verify not all zeros
		boolean hasNonZero = false;
		for (byte b : material) {
			if (b != 0) {
				hasNonZero = true;
				break;
			}
		}
		assertTrue("Key material should not be all zeros", hasNonZero);
	}
}
