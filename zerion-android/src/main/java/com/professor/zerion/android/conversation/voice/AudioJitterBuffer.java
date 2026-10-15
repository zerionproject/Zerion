package com.professor.zerion.android.conversation.voice;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class AudioJitterBuffer {

	private final int capacity;
	private final int startThreshold;
	private final Object lock = new Object();
	@GuardedBy("lock")
	private final byte[] buffer;
	@GuardedBy("lock")
	private int writePos = 0;
	@GuardedBy("lock")
	private int readPos = 0;
	@GuardedBy("lock")
	private int buffered = 0;
	private volatile boolean started = false;

	AudioJitterBuffer(int capacity, int startThreshold) {
		this.capacity = capacity;
		this.startThreshold = startThreshold;
		this.buffer = new byte[capacity];
	}

	void reset() {
		synchronized (lock) {
			writePos = 0;
			readPos = 0;
			buffered = 0;
			started = false;
		}
	}

	void wipe() {
		synchronized (lock) {
			Arrays.fill(buffer, (byte) 0);
			writePos = 0;
			readPos = 0;
			buffered = 0;
		}
	}

	void write(byte[] data) {
		synchronized (lock) {
			int length = Math.min(data.length, capacity);
			int from = data.length - length;
			for (int i = 0; i < length; i++) {
				buffer[(writePos + i) % capacity] = data[from + i];
			}
			writePos = (writePos + length) % capacity;
			int overflow = buffered + length - capacity;
			if (overflow > 0) {
				readPos = (readPos + overflow) % capacity;
				buffered = capacity;
			} else {
				buffered += length;
			}
			if (!started && buffered >= startThreshold) {
				started = true;
			}
		}
	}

	boolean started() {
		return started;
	}

	boolean read(byte[] out) {
		synchronized (lock) {
			if (buffered < out.length) return false;
			for (int i = 0; i < out.length; i++) {
				out[i] = buffer[readPos];
				readPos = (readPos + 1) % capacity;
			}
			buffered -= out.length;
			return true;
		}
	}

	int buffered() {
		synchronized (lock) {
			return buffered;
		}
	}
}
