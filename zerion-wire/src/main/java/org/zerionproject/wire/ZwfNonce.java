package org.zerionproject.wire;

import static org.zerionproject.wire.ZwfConstants.NONCE_LENGTH;

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
