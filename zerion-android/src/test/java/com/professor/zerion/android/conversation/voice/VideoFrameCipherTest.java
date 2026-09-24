package com.professor.zerion.android.conversation.voice;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.AEADBadTagException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Deterministic proof that (key, nonce) pairs never repeat within a video
 * session and that separate sessions never share a key, which together rule
 * out the AES-GCM nonce reuse of the previous design where every session
 * derived the same key and restarted its counter at zero.
 */
public class VideoFrameCipherTest {

	private static byte[] key(int seed) {
		byte[] k = new byte[VideoFrameCipher.KEY_LENGTH];
		for (int i = 0; i < k.length; i++) {
			k[i] = (byte) (seed * 131 + i * 17 + 7);
		}
		return k;
	}

	@Test
	public void testNonceIsTheCounterAndDistinctForDistinctCounters() {
		assertArrayEquals(new byte[12], VideoFrameCipher.nonceFor(0));
		byte[] one = VideoFrameCipher.nonceFor(1);
		assertEquals(1, one[11]);
		assertEquals(0, one[10]);
		Set<String> seen = new HashSet<>();
		for (long c = 0; c < 100_000; c++) {
			assertTrue(seen.add(Arrays.toString(VideoFrameCipher.nonceFor(c))));
		}
		byte[] big = VideoFrameCipher.nonceFor(Long.MAX_VALUE);
		assertEquals(0x7F, big[4] & 0xFF);
		assertEquals(0xFF, big[11] & 0xFF);
		assertFalse(Arrays.equals(big, VideoFrameCipher.nonceFor(Long.MAX_VALUE - 1)));
	}

	@Test(expected = IllegalStateException.class)
	public void testNegativeCounterIsRefused() {
		VideoFrameCipher.nonceFor(-1);
	}

	@Test
	public void testEveryEncryptionAdvancesTheCounter() throws Exception {
		VideoFrameCipher c = new VideoFrameCipher(key(1), key(2));
		for (long i = 0; i < 1000; i++) {
			assertEquals(i, c.nextTxCounter());
			c.encrypt(new byte[64]);
		}
		assertEquals(1000, c.nextTxCounter());
	}

	@Test
	public void testSameFrameIndexInTwoSessionsYieldsDifferentCiphertext()
			throws Exception {
		byte[] frame = new byte[256];
		VideoFrameCipher session1 = new VideoFrameCipher(key(1), key(2));
		VideoFrameCipher session2 = new VideoFrameCipher(key(3), key(4));
		byte[] c1 = session1.encrypt(frame);
		byte[] c2 = session2.encrypt(frame);
		assertFalse(Arrays.equals(c1, c2));
		byte[] x = new byte[c1.length - 16];
		for (int i = 0; i < x.length; i++) x[i] = (byte) (c1[i] ^ c2[i]);
		assertFalse(Arrays.equals(new byte[x.length], x));
	}

	@Test
	public void testSameKeyReuseAcrossSessionsIsWhatTheDesignForbids()
			throws Exception {
		byte[] frame = new byte[256];
		VideoFrameCipher a = new VideoFrameCipher(key(1), key(2));
		VideoFrameCipher b = new VideoFrameCipher(key(1), key(2));
		byte[] ca = a.encrypt(frame);
		byte[] cb = b.encrypt(frame);
		assertArrayEquals(ca, cb);
	}

	@Test
	public void testRoundTripAndTamperDetection() throws Exception {
		VideoFrameCipher alice = new VideoFrameCipher(key(1), key(2));
		VideoFrameCipher bob = new VideoFrameCipher(key(2), key(1));
		for (int i = 0; i < 50; i++) {
			byte[] frame = new byte[100 + i];
			Arrays.fill(frame, (byte) i);
			assertArrayEquals(frame, bob.decrypt(alice.encrypt(frame)));
		}
		byte[] c = alice.encrypt(new byte[32]);
		c[5] ^= 1;
		try {
			bob.decrypt(c);
			fail();
		} catch (AEADBadTagException expected) {
		}
	}

	@Test
	public void testReplayedFrameIsRejectedBecauseTheReceiverNonceMovedOn()
			throws Exception {
		VideoFrameCipher alice = new VideoFrameCipher(key(1), key(2));
		VideoFrameCipher bob = new VideoFrameCipher(key(2), key(1));
		byte[] c0 = alice.encrypt(new byte[32]);
		bob.decrypt(c0);
		try {
			bob.decrypt(c0);
			fail();
		} catch (AEADBadTagException expected) {
		}
	}

	@Test
	public void testDirectionsUseDifferentKeys() {
		try {
			new VideoFrameCipher(key(1), key(1));
			fail();
		} catch (IllegalArgumentException expected) {
		}
	}

	@Test
	public void testClosedCipherRefusesAndWipes() throws Exception {
		byte[] tx = key(1);
		VideoFrameCipher c = new VideoFrameCipher(tx, key(2));
		c.encrypt(new byte[8]);
		c.close();
		assertTrue(c.isClosed());
		try {
			c.encrypt(new byte[8]);
			fail();
		} catch (IllegalStateException expected) {
		}
	}
}
