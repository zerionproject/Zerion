package org.zerionproject.core.db;

import static org.zerionproject.core.api.sync.SyncConstants.MAX_TRANSPORT_LATENCY;

class ExponentialBackoff {

	static long calculateExpiry(long now, long maxLatency, int txCount) {
		if (now < 0) throw new IllegalArgumentException();
		if (maxLatency <= 0 || maxLatency > MAX_TRANSPORT_LATENCY) {
			throw new IllegalArgumentException();
		}
		if (txCount < 0) throw new IllegalArgumentException();
		long roundTrip = maxLatency * 2L;
		for (int i = 0; i < txCount; i++) {
			roundTrip <<= 1;
			if (roundTrip < 0) return Long.MAX_VALUE;
		}
		long expiry = now + roundTrip;
		return expiry < 0 ? Long.MAX_VALUE : expiry;
	}
}
