package com.professor.zerion.android.mesh;

import java.util.Arrays;

final class MeshPadding {

	private static final int PREFIX = 4;
	private static final int[] BUCKETS = {4096, 16384};
	static final int MAX_DATA_BYTES = 16384 - PREFIX;

	private MeshPadding() {
	}

	static byte[] pad(byte[] data) {
		if (data.length > MAX_DATA_BYTES) {
			throw new IllegalArgumentException("payload too large for mesh");
		}
		int bucket = bucketFor(data.length + PREFIX);
		byte[] out = new byte[bucket];
		out[0] = (byte) (data.length >>> 24);
		out[1] = (byte) (data.length >>> 16);
		out[2] = (byte) (data.length >>> 8);
		out[3] = (byte) data.length;
		System.arraycopy(data, 0, out, PREFIX, data.length);
		return out;
	}

	static byte[] unpad(byte[] padded) {
		if (padded.length < PREFIX) return new byte[0];
		int len = ((padded[0] & 0xFF) << 24) | ((padded[1] & 0xFF) << 16)
				| ((padded[2] & 0xFF) << 8) | (padded[3] & 0xFF);
		if (len < 0 || len > padded.length - PREFIX) return new byte[0];
		return Arrays.copyOfRange(padded, PREFIX, PREFIX + len);
	}

	private static int bucketFor(int n) {
		for (int b : BUCKETS) {
			if (n <= b) return b;
		}
		return BUCKETS[BUCKETS.length - 1];
	}
}
