package org.zerionproject.sync;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.ThreadSafe;

/**
 * The production pacing policy: an active regime at the classic 750 ms slot
 * and an idle regime of 4 s. Each regime is a constant, jittered rate; only
 * the choice between them depends on whether application records flowed
 * recently, so an observer of an established connection learns at most the
 * coarse onset and end of activity, never which frames carried data. The
 * idle rate is the same on every network type: a slower rate on metered
 * networks would tell the peer which kind of network this device is on.
 */
@ThreadSafe
@NotNullByDefault
public class ZppPacingPolicy implements ZppPacing {

	static final long ACTIVE_INTERVAL_MS = 750;
	static final long IDLE_INTERVAL_MS = 4_000;
	static final long IDLE_AFTER_MS = 2 * 60_000;

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
}
