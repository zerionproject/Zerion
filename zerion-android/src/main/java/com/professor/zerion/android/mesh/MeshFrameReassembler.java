package com.professor.zerion.android.mesh;

import javax.annotation.Nullable;

/**
 * Reassembles length-prefixed mesh frames from MTU-sized BLE chunks. A
 * compacting read/write buffer keeps appends amortised O(1) and copies each
 * completed frame once. One instance per connection; synchronised because chunks
 * and polls can arrive on different threads.
 */
final class MeshFrameReassembler {

	private static final int LENGTH_PREFIX = 4;

	private final int maxFrameBytes;
	private byte[] buf = new byte[512];
	private int head = 0;
	private int tail = 0;

	MeshFrameReassembler(int maxFrameBytes) {
		this.maxFrameBytes = maxFrameBytes;
	}

	synchronized void append(byte[] chunk) {
		ensureCapacity(chunk.length);
		System.arraycopy(chunk, 0, buf, tail, chunk.length);
		tail += chunk.length;
	}

	private void ensureCapacity(int extra) {
		if (tail + extra <= buf.length) return;
		int used = tail - head;
		if (head > 0 && used + extra <= buf.length) {
			System.arraycopy(buf, head, buf, 0, used);
			head = 0;
			tail = used;
			return;
		}
		int newCap = Math.max(buf.length * 2, used + extra);
		byte[] grown = new byte[newCap];
		System.arraycopy(buf, head, grown, 0, used);
		buf = grown;
		head = 0;
		tail = used;
	}

	@Nullable
	synchronized byte[] poll() {
		int avail = tail - head;
		if (avail < LENGTH_PREFIX) return null;
		int len = ((buf[head] & 0xFF) << 24) | ((buf[head + 1] & 0xFF) << 16)
				| ((buf[head + 2] & 0xFF) << 8) | (buf[head + 3] & 0xFF);
		if (len < 0 || len > maxFrameBytes) {
			head = 0;
			tail = 0;
			return null;
		}
		if (avail < LENGTH_PREFIX + len) return null;
		byte[] frame = new byte[len];
		System.arraycopy(buf, head + LENGTH_PREFIX, frame, 0, len);
		head += LENGTH_PREFIX + len;
		if (head == tail) {
			head = 0;
			tail = 0;
		}
		return frame;
	}
}
