package org.briarproject.bramble.plugin;

import org.briarproject.bramble.api.plugin.Backoff;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.SecureRandom;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
class BackoffImpl implements Backoff {

	private final int minInterval, maxInterval;
	private final double base;
	private final AtomicInteger backoff;
	// Random jitter to prevent timing correlation
	private final SecureRandom random = new SecureRandom();

	BackoffImpl(int minInterval, int maxInterval, double base) {
		this.minInterval = minInterval;
		this.maxInterval = maxInterval;
		this.base = base;
		backoff = new AtomicInteger(0);
	}

	@Override
	public int getPollingInterval() {
		double multiplier = Math.pow(base, backoff.get());
		int interval = (int) (minInterval * multiplier);
		// Add random jitter (0-25% of interval)
		int jitter = interval > 0 ? random.nextInt(interval / 4 + 1) : 0;
		return Math.min(interval + jitter, maxInterval);
	}

	@Override
	public void increment() {
		backoff.incrementAndGet();
	}

	@Override
	public void reset() {
		backoff.set(0);
	}
}
