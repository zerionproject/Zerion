package org.zerionproject.app.conversation.voice;

import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.rendezvous.KeyMaterialSource;
import org.junit.Test;

import static org.junit.Assert.*;

public class VoiceCallKeyMaterialSourceTest {

	@Test
	public void testGeneratesKeyMaterial() {

		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) i;
		}
		SecretKey sourceKey = new SecretKey(keyBytes);
		KeyMaterialSource source = new VoiceCallKeyMaterialSource(sourceKey);

		byte[] material = source.getKeyMaterial(64);

		assertNotNull(material);
		assertEquals(64, material.length);
	}

	@Test
	public void testDeterministicOutput() {

		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) i;
		}
		SecretKey sourceKey = new SecretKey(keyBytes);

		KeyMaterialSource source1 = new VoiceCallKeyMaterialSource(sourceKey);
		KeyMaterialSource source2 = new VoiceCallKeyMaterialSource(sourceKey);

		byte[] material1 = source1.getKeyMaterial(64);
		byte[] material2 = source2.getKeyMaterial(64);

		assertArrayEquals("Same key should produce same key material",
				material1, material2);
	}

	@Test
	public void testProgressiveOutput() {

		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) i;
		}
		SecretKey sourceKey = new SecretKey(keyBytes);
		KeyMaterialSource source = new VoiceCallKeyMaterialSource(sourceKey);

		byte[] chunk1 = source.getKeyMaterial(32);
		byte[] chunk2 = source.getKeyMaterial(32);

		assertFalse("Consecutive calls should produce different material",
				java.util.Arrays.equals(chunk1, chunk2));
	}

	@Test
	public void testDifferentKeyProducesDifferentOutput() {

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

		byte[] material1 = source1.getKeyMaterial(64);
		byte[] material2 = source2.getKeyMaterial(64);

		assertFalse("Different keys should produce different material",
				java.util.Arrays.equals(material1, material2));
	}

	@Test
	public void testLargeOutput() {

		byte[] keyBytes = new byte[32];
		for (int i = 0; i < 32; i++) {
			keyBytes[i] = (byte) i;
		}
		SecretKey sourceKey = new SecretKey(keyBytes);
		KeyMaterialSource source = new VoiceCallKeyMaterialSource(sourceKey);

		byte[] material = source.getKeyMaterial(256);

		assertNotNull(material);
		assertEquals(256, material.length);

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
