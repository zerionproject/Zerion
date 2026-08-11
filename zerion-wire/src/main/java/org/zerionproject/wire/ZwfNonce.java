package org.zerionproject.wire;

import static org.zerionproject.wire.ZwfConstants.NONCE_LENGTH;

/**
 * Derives the 24-byte XSalsa20 AEAD nonce for a ZWF frame segment.
 *
 * <p>The {@code streamId} is bound directly into the nonce as defence in depth:
 * even if the chain-key derivation were somehow reused, two different streams
 * still produce disjoint nonce spaces.
 *
 * <p>Nonce layout (big-endian):
 * <pre>
 *   [0..7]   streamId   (uint64, persistent, strictly monotonic per contact/direction)
 *   [8..15]  frameNumber (uint64, per-stream, from 0)
 *   [16]     0x80        (domain-separation marker)
 *   [17]     segment     (0, 1 or 2 — the three Mode 3-Full AEAD segments)
 *   [18]     originator  (1 if the stream was opened by the alice-role endpoint,
 *                         else 0 — the sender of a stream is always its originator)
 *   [19..23] 0
 * </pre>
 */
public final class ZwfNonce {

	private ZwfNonce() {
	}

	public static void encode(byte[] dest, long streamId, long frameNumber,
			int segment, boolean originatorIsAlice) {
		if (dest.length < NONCE_LENGTH)
			throw new IllegalArgumentException("nonce buffer too short");
		if (streamId < 0) throw new IllegalArgumentException("streamId < 0");
		if (frameNumber < 0)
			throw new IllegalArgumentException("frameNumber < 0");
		if (segment < 0 || segment > 2)
			throw new IllegalArgumentException("segment out of range");
		writeUint64(streamId, dest, 0);
		writeUint64(frameNumber, dest, 8);
		dest[16] = (byte) 0x80;
		dest[17] = (byte) segment;
		dest[18] = (byte) (originatorIsAlice ? 1 : 0);
		for (int i = 19; i < NONCE_LENGTH; i++) dest[i] = 0;
	}

	static void writeUint64(long value, byte[] dest, int offset) {
		for (int i = 0; i < 8; i++) {
			dest[offset + i] = (byte) (value >>> (56 - i * 8));
		}
	}
}
