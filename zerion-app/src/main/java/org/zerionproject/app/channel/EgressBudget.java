package org.zerionproject.app.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.function.LongSupplier;

import javax.annotation.concurrent.GuardedBy;
import javax.annotation.concurrent.ThreadSafe;

/**
 * A rolling-window byte budget shared across a publisher's request handlers.
 * Channels are anonymous-read, so an unauthenticated reader can open many
 * circuits and repeatedly fetch the largest object. The budget caps the total
 * bytes a publisher will serve per window regardless of how many concurrent
 * readers there are, giving a cheap reject once the cap is reached without
 * denying legitimate subscribers in a later window.
 */
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

	/**
	 * Charges {@code bytes} against the current window, returning false without
	 * charging when the window has no room left. A single charge larger than
	 * the whole window budget is allowed once per window so that one legitimate
	 * large object can always be served.
	 */
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
}
