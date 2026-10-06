package org.zerionproject.app.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.function.LongSupplier;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
class EgressBudget {

	private final long windowMs;
	private final long maxBytesPerWindow;
	private final LongSupplier clock;

	@GuardedBy("this")
	private long windowStart = Long.MIN_VALUE;
	@GuardedBy("this")
	private long bytesThisWindow = 0;

	EgressBudget(long windowMs, long maxBytesPerWindow, LongSupplier clock) {
		this.windowMs = windowMs;
		this.maxBytesPerWindow = maxBytesPerWindow;
		this.clock = clock;
	}

	synchronized boolean tryCharge(long bytes) {
		long now = clock.getAsLong();
		if (windowStart == Long.MIN_VALUE || now - windowStart > windowMs) {
			windowStart = now;
			bytesThisWindow = 0;
		}
		if (bytesThisWindow > 0
				&& bytesThisWindow + bytes > maxBytesPerWindow) {
			return false;
		}
		bytesThisWindow += bytes;
		return true;
	}

	synchronized boolean exhausted() {
		long now = clock.getAsLong();
		if (windowStart == Long.MIN_VALUE || now - windowStart > windowMs) {
			return false;
		}
		return bytesThisWindow >= maxBytesPerWindow;
	}
}
