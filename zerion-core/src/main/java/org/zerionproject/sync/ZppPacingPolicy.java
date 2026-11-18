package org.zerionproject.sync;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
public class ZppPacingPolicy implements ZppPacing {

	static final long ACTIVE_INTERVAL_MS = 750;
	static final long IDLE_INTERVAL_MS = 4_000;
	static final long IDLE_AFTER_MS = 2 * 60_000;
	public static final boolean DEFAULT_COVER_TRAFFIC = false;

	private volatile boolean coverTraffic = DEFAULT_COVER_TRAFFIC;

	@Override
	public long activeIntervalMs() {
		return ACTIVE_INTERVAL_MS;
	}

	@Override
	public long idleIntervalMs() {
		return IDLE_INTERVAL_MS;
	}

	@Override
	public long idleAfterMs() {
		return IDLE_AFTER_MS;
	}

	@Override
	public boolean coverTraffic() {
		return coverTraffic;
	}

	public void setCoverTraffic(boolean enabled) {
		coverTraffic = enabled;
	}
}
