package org.zerionproject.app.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.HashMap;
import java.util.Map;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class ChannelWriteBudget {

	private final long burst;
	private final long perHour;
	private final Map<String, long[]> allowances = new HashMap<>();

	ChannelWriteBudget(long burst, long perHour) {
		this.burst = burst;
		this.perHour = perHour;
	}

	synchronized boolean hasRoom(String key, long now) {
		return refill(key, now)[0] > 0;
	}

	synchronized void spend(String key, long bytes, long now) {
		long[] a = refill(key, now);
		a[0] -= bytes;
	}

	private long[] refill(String key, long now) {
		long[] a = allowances.get(key);
		if (a == null) {
			a = new long[] {burst, now};
			allowances.put(key, a);
			return a;
		}
		long elapsed = now - a[1];
		if (elapsed > 0) {
			long added = elapsed >= 3_600_000L * 64L ? burst
					: elapsed * perHour / 3_600_000L;
			a[0] = Math.min(burst, a[0] + added);
			a[1] = now;
		} else if (elapsed < 0) {
			a[1] = now;
		}
		return a;
	}
}
