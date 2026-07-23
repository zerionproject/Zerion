package org.zerionproject.wire;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

import static org.zerionproject.wire.ZwfConstants.NONCE_LENGTH;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZwfNonceTest {

	@Test
	public void layoutIsBigEndianAndDomainSeparated() {
		byte[] n = new byte[NONCE_LENGTH];
		ZwfNonce.encode(n, 0x0102030405060708L, 0x1112131415161718L, 2, true);
		byte[] expected = {
				0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, // streamId
				0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18, // frameNumber
				(byte) 0x80,                                    // marker
				0x02,                                           // segment
				0x01,                                           // originator (alice)
				0, 0, 0, 0, 0
		};
		for (int i = 0; i < NONCE_LENGTH; i++) {
			assertEquals("byte " + i, expected[i], n[i]);
		}
	}

	@Test
	public void nonceIsUniqueEvenWhenFrameNumberResets() {
		// The crux: frameNumber restarts at 0 on every new stream. Binding the
		// (monotonic) streamId into the nonce keeps every nonce distinct anyway.
		Set<String> seen = new HashSet<>();
		byte[] n = new byte[NONCE_LENGTH];
		for (long streamId = 1; streamId <= 300; streamId++) {
			for (long frame = 0; frame < 300; frame++) {
				for (int seg = 0; seg <= 2; seg++) {
					ZwfNonce.encode(n, streamId, frame, seg, true);
					if (!seen.add(toHex(n))) {
						fail("nonce collision at streamId=" + streamId
								+ " frame=" + frame + " seg=" + seg);
					}
				}
			}
		}
		assertEquals(300 * 300 * 3, seen.size());
	}

	@Test
	public void segmentsDifferUnderSameStreamAndFrame() {
		byte[] s0 = new byte[NONCE_LENGTH];
		byte[] s1 = new byte[NONCE_LENGTH];
		byte[] s2 = new byte[NONCE_LENGTH];
		ZwfNonce.encode(s0, 42, 7, 0, true);
		ZwfNonce.encode(s1, 42, 7, 1, true);
		ZwfNonce.encode(s2, 42, 7, 2, true);
		assertTrue(!toHex(s0).equals(toHex(s1)));
		assertTrue(!toHex(s1).equals(toHex(s2)));
		assertTrue(!toHex(s0).equals(toHex(s2)));
	}

	@Test
	public void originatorByteSeparatesTheTwoDirections() {
		// The two directions of a connection both open streamId 1, frame 0. The
		// originator bit makes their nonces disjoint regardless of the keys, so a
		// (key, nonce) collision cannot arise even if both directions were ever
		// seeded from the same root key.
		byte[] alice = new byte[NONCE_LENGTH];
		byte[] bob = new byte[NONCE_LENGTH];
		ZwfNonce.encode(alice, 1, 0, 0, true);
		ZwfNonce.encode(bob, 1, 0, 0, false);
		assertTrue("directions must differ", !toHex(alice).equals(toHex(bob)));
		assertEquals((byte) 1, alice[18]);
		assertEquals((byte) 0, bob[18]);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsOutOfRangeSegment() {
		ZwfNonce.encode(new byte[NONCE_LENGTH], 1, 0, 3, true);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsShortBuffer() {
		ZwfNonce.encode(new byte[NONCE_LENGTH - 1], 1, 0, 0, true);
	}

	private static String toHex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16))
				.append(Character.forDigit(x & 0xF, 16));
		return sb.toString();
	}
}
