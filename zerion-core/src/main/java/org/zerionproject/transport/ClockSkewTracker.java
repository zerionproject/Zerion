package org.zerionproject.transport;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class ClockSkewTracker {

	private long skewSeconds = 0;

	synchronized void onSkew(long skewSeconds) {
		this.skewSeconds = skewSeconds;
	}

	synchronized void clear() {
		skewSeconds = 0;
	}

	synchronized long current() {
		return skewSeconds;
	}
}
